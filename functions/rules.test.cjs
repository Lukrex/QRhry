const fs = require('node:fs');
const path = require('node:path');
const { after, before, test } = require('node:test');
const { initializeTestEnvironment, assertFails, assertSucceeds } = require('@firebase/rules-unit-testing');
const { doc, setDoc, getDoc, getDocs, collection, query, where } = require('firebase/firestore');
const { ref, uploadBytes, getBytes, deleteObject } = require('firebase/storage');

let env;
before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-qrhry',
    firestore: { rules: fs.readFileSync(path.join(__dirname, '..', 'firestore.rules'), 'utf8') },
    storage: { rules: fs.readFileSync(path.join(__dirname, '..', 'storage.rules'), 'utf8') }
  });
  await env.withSecurityRulesDisabled(async context => {
    await setDoc(doc(context.firestore(), 'publicGames/publication-123'), {
      state: 'PUBLISHED', revisionId: 'revision-123', publishedAt: new Date()
    });
    await setDoc(doc(context.firestore(), 'publicGames/hidden-123'), { state: 'HIDDEN' });
    await setDoc(doc(context.firestore(), 'users/author-1/uploads/request-123'), { expiresAt: new Date(Date.now() + 60_000) });
    await setDoc(doc(context.firestore(), 'users/author-1/uploads/request-456'), { expiresAt: new Date(Date.now() + 60_000) });
    await setDoc(doc(context.firestore(), 'users/author-2/uploads/request-456'), { expiresAt: new Date(Date.now() + 60_000) });
    await uploadBytes(ref(context.storage(), 'published/publication-123/revision-123.qrhry'), new Uint8Array([1, 2, 3]));
  });
});
after(async () => { await env?.cleanup(); });

test('anonymous users can read published rows only and cannot write public metadata', async () => {
  const db = env.unauthenticatedContext().firestore();
  await assertSucceeds(getDoc(doc(db, 'publicGames/publication-123')));
  await assertFails(getDoc(doc(db, 'publicGames/hidden-123')));
  await assertSucceeds(getDocs(query(collection(db, 'publicGames'), where('state', '==', 'PUBLISHED'))));
  await assertFails(setDoc(doc(db, 'publicGames/new-123'), { state: 'PUBLISHED' }));
});

test('only an owner can create and delete their own staging upload, and cannot overwrite it', async () => {
  const owner = ref(env.authenticatedContext('author-1').storage(), 'staging/author-1/request-123/request-123.qrhry');
  await assertSucceeds(uploadBytes(owner, new Uint8Array([1]), { contentType: 'application/zip' }));
  await assertFails(uploadBytes(owner, new Uint8Array([2]), { contentType: 'application/zip' }));
  const other = ref(env.authenticatedContext('author-2').storage(), 'staging/author-1/request-456/request-456.qrhry');
  await assertFails(uploadBytes(other, new Uint8Array([1]), { contentType: 'application/zip' }));
  await assertFails(deleteObject(ref(env.authenticatedContext('author-2').storage(), 'staging/author-1/request-123/request-123.qrhry')));
  await assertSucceeds(deleteObject(owner));
});

test('public files are readable only while their revision is active and cannot be overwritten', async () => {
  const storage = env.unauthenticatedContext().storage();
  await assertSucceeds(getBytes(ref(storage, 'published/publication-123/revision-123.qrhry')));
  await assertFails(getBytes(ref(storage, 'published/publication-123/old-revision.qrhry')));
  await assertFails(uploadBytes(ref(storage, 'published/publication-123/revision-123.qrhry'), new Uint8Array([9])));
});
