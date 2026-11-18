import { createHash, randomUUID } from 'node:crypto';
import { initializeApp } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { getFirestore, FieldValue, Timestamp } from 'firebase-admin/firestore';
import { getStorage } from 'firebase-admin/storage';
import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { validateArchive } from './archiveValidator';

initializeApp();
const db = getFirestore();
const bucket = getStorage().bucket();
const REGION = 'europe-west1';
const MAX = 100 * 1024 * 1024;
const ENFORCE_APP_CHECK = process.env.ENFORCE_APP_CHECK === 'true';
const TOKEN = /[^\p{L}\p{N}]+/gu;
const MARK = /\p{M}+/gu;
function fail(message: string): never { throw new HttpsError('failed-precondition', message); }
function words(value: string): string[] {
  return [...new Set(value.normalize('NFD').replace(MARK, '').toLocaleLowerCase('und').replace(TOKEN, ' ').trim().split(/\s+/).filter(Boolean))].slice(0, 10);
}
function searchTerms(title: string, description: string): string[] {
  const titleWords = words(title);
  const prefixes = titleWords.flatMap(word => Array.from({ length: Math.max(0, word.length - 1) }, (_, i) => word.slice(0, i + 2)));
  return [...new Set([...prefixes, ...words(description)])].slice(0, 100);
}
function validateText(input: any): { description: string; languageTag: string; authorName: string } {
  const description = typeof input.description === 'string' ? input.description.trim() : '';
  const authorName = typeof input.authorName === 'string' ? input.authorName.trim() : '';
  const languageTag = typeof input.languageTag === 'string' ? input.languageTag.trim() : '';
  let canonicalLanguage = '';
  try { canonicalLanguage = Intl.getCanonicalLocales(languageTag)[0] ?? ''; } catch { fail('invalid_metadata'); }
  if (!description || description.length > 500 || /[\x00-\x1f\x7f]/.test(description) || authorName.length < 2 || authorName.length > 40 || /[\x00-\x1f\x7f]/.test(authorName) || languageTag.length > 35 || !canonicalLanguage) fail('invalid_metadata');
  return { description, languageTag: canonicalLanguage, authorName };
}

// Callable endpoints must be reachable by Firebase client SDKs at the Cloud
// Run gateway; each handler below enforces Firebase Auth and verified email.
export const beginPublicationUpload = onCall({ region: REGION, invoker: ['allUsers'], enforceAppCheck: ENFORCE_APP_CHECK }, async request => {
  if (!request.auth) throw new HttpsError('unauthenticated', 'sign_in_required');
  const uid = request.auth.uid;
  const user = await getAuth().getUser(uid);
  if (!user.emailVerified) throw new HttpsError('permission-denied', 'verified_email_required');
  const requestId = String(request.data?.requestId ?? '');
  if (!/^[0-9a-f-]{36}$/i.test(requestId)) fail('invalid_request');
  const requestRef = db.doc(`users/${uid}/requests/${requestId}`);
  const completed = await requestRef.get();
  const staging = bucket.file(`staging/${uid}/${requestId}/${requestId}.qrhry`);
  if (completed.exists) {
    await staging.delete({ ignoreNotFound: true });
    return { alreadyPublished: true, ...completed.data()!.result };
  }
  const reservationRef = db.doc(`users/${uid}/uploads/${requestId}`);
  const windowKey = String(Math.floor(Date.now() / 3_600_000));
  const rateRef = db.doc(`users/${uid}/rateLimits/${windowKey}`);
  let alreadyReserved = false;
  await db.runTransaction(async tx => {
    const [reservation, rate] = await Promise.all([tx.get(reservationRef), tx.get(rateRef)]);
    if (reservation.exists && Number(reservation.get('expiresAt')?.toMillis?.() ?? 0) > Date.now()) {
      alreadyReserved = true;
      return;
    }
    const count = Number(rate.data()?.count ?? 0);
    if (count >= 5) throw new HttpsError('resource-exhausted', 'rate_limited');
    tx.set(rateRef, { count: count + 1, expiresAt: Timestamp.fromMillis(Date.now() + 48 * 3_600_000) });
    tx.set(reservationRef, { expiresAt: Timestamp.fromMillis(Date.now() + 24 * 3_600_000) });
  });
  if (alreadyReserved) await staging.delete({ ignoreNotFound: true });
  return { alreadyPublished: false };
});

export const publishGame = onCall({ region: REGION, invoker: 'public', enforceAppCheck: ENFORCE_APP_CHECK, memory: '1GiB', timeoutSeconds: 120 }, async request => {
  if (!request.auth) throw new HttpsError('unauthenticated', 'sign_in_required');
  const uid = request.auth.uid;
  const user = await getAuth().getUser(uid);
  if (!user.emailVerified) throw new HttpsError('permission-denied', 'verified_email_required');
  const requestId = String(request.data?.requestId ?? '');
  if (!/^[0-9a-f-]{36}$/i.test(requestId)) fail('invalid_request');
  const requestRef = db.doc(`users/${uid}/requests/${requestId}`);
  const existingRequest = await requestRef.get();
  if (existingRequest.exists) return existingRequest.data()!.result;
  const reservationRef = db.doc(`users/${uid}/uploads/${requestId}`);
  const reservation = await reservationRef.get();
  if (!reservation.exists || Number(reservation.get('expiresAt')?.toMillis?.() ?? 0) <= Date.now()) fail('upload_not_reserved');
  const stagingPath = `staging/${uid}/${requestId}/${requestId}.qrhry`;
  const staging = bucket.file(stagingPath);
  const [meta] = await staging.getMetadata().catch(() => { throw new HttpsError('not-found', 'staging_upload_missing'); });
  if (Number(meta.size) <= 0 || Number(meta.size) > MAX || meta.contentType !== 'application/zip') fail('invalid_upload');
  const [bytes] = await staging.download();
  if (bytes.length !== Number(meta.size)) fail('invalid_upload');
  let summary: Awaited<ReturnType<typeof validateArchive>>;
  try { summary = await validateArchive(bytes); } catch { throw new HttpsError('invalid-argument', 'invalid_archive'); }
  const metadata = validateText(request.data);
  const publicationId = typeof request.data.publicationId === 'string' ? request.data.publicationId : randomUUID();
  if (!/^[A-Za-z0-9_-]{8,64}$/.test(publicationId)) fail('invalid_publication');
  const publicationRef = db.doc(`publicGames/${publicationId}`);
  const ownerRef = db.doc(`users/${uid}/publications/${publicationId}`);
  const [publicDoc, ownerDoc] = await Promise.all([publicationRef.get(), ownerRef.get()]);
  if (publicDoc.exists && (!ownerDoc.exists || ownerDoc.get('ownerUid') !== uid)) throw new HttpsError('permission-denied', 'not_owner');
  if (ownerDoc.exists && ownerDoc.get('ownerUid') !== uid) throw new HttpsError('permission-denied', 'not_owner');
  const revisionId = randomUUID();
  const objectPath = `published/${publicationId}/${revisionId}.qrhry`;
  await bucket.file(objectPath).save(bytes, { resumable: false, validation: 'crc32c', metadata: { contentType: 'application/zip', metadata: { sha256: summary.sha256, revisionId } }, preconditionOpts: { ifGenerationMatch: 0 } });
  const now = Timestamp.now();
  const previousPath = publicDoc.get('objectPath') as string | undefined;
  const result = { publicationId, revisionId };
  let committed = false;
  try {
    const doc = {
      publicationId, revisionId, objectPath, state: 'PUBLISHED', title: summary.title,
      ...metadata, stationCount: summary.stationCount, taskCount: summary.taskCount, mediaCount: summary.mediaCount,
      archiveSize: summary.size, archiveSha256: summary.sha256,
      searchTokens: searchTerms(summary.title, metadata.description),
      publishedAt: publicDoc.get('publishedAt') ?? now, updatedAt: now
    };
    await db.runTransaction(async tx => {
      const [current, owner] = await Promise.all([tx.get(publicationRef), tx.get(ownerRef)]);
      if (current.exists && (!owner.exists || owner.get('ownerUid') !== uid)) throw new HttpsError('permission-denied', 'not_owner');
      if (owner.exists && owner.get('ownerUid') !== uid) throw new HttpsError('permission-denied', 'not_owner');
      tx.set(publicationRef, doc);
      tx.set(ownerRef, { ...doc, ownerUid: uid });
      tx.create(requestRef, { result, createdAt: FieldValue.serverTimestamp(), expiresAt: Timestamp.fromMillis(Date.now() + 30 * 86_400_000) });
      tx.delete(reservationRef);
    });
    committed = true;
    // Once the Firestore pointer switches, cleanup failures must not remove the
    // newly active object or report the publication as failed.
    await staging.delete({ ignoreNotFound: true }).catch(() => undefined);
    if (previousPath && previousPath !== objectPath) {
      await bucket.file(previousPath).delete({ ignoreNotFound: true }).catch(() => undefined);
    }
    return result;
  } catch (error) {
    if (!committed) await bucket.file(objectPath).delete({ ignoreNotFound: true }).catch(() => undefined);
    throw error;
  }
});

export const unpublishGame = onCall({ region: REGION, invoker: 'public', enforceAppCheck: ENFORCE_APP_CHECK }, async request => {
  if (!request.auth) throw new HttpsError('unauthenticated', 'sign_in_required');
  const publicationId = String(request.data?.publicationId ?? '');
  if (!/^[A-Za-z0-9_-]{8,64}$/.test(publicationId)) fail('invalid_publication');
  const uid = request.auth.uid;
  const publicRef = db.doc(`publicGames/${publicationId}`);
  const ownerRef = db.doc(`users/${uid}/publications/${publicationId}`);
  const owner = await ownerRef.get();
  if (!owner.exists || owner.get('ownerUid') !== uid) throw new HttpsError('permission-denied', 'not_owner');
  const publicDoc = await publicRef.get();
  const objectPath = publicDoc.get('objectPath') as string | undefined;
  await db.runTransaction(async tx => {
    const latest = await tx.get(publicRef);
    const latestOwner = await tx.get(ownerRef);
    if (latest.exists && (!latestOwner.exists || latestOwner.get('ownerUid') !== uid)) throw new HttpsError('permission-denied', 'not_owner');
    tx.delete(publicRef);
    tx.update(ownerRef, { state: 'HIDDEN', updatedAt: FieldValue.serverTimestamp() });
  });
  if (objectPath) await bucket.file(objectPath).delete({ ignoreNotFound: true });
  return { ok: true };
});
