import { createHash } from 'node:crypto';
import * as yauzl from 'yauzl';

const MAX_ZIP = 100 * 1024 * 1024;
const MAX_EXPANDED = 250 * 1024 * 1024;
const MAX_MANIFEST = 1024 * 1024;

export type ArchiveSummary = { title: string; stationCount: number; taskCount: number; mediaCount: number; sha256: string; size: number };
function hasKeys(value: any, expected: string[]): boolean {
  return value != null && typeof value === 'object' && !Array.isArray(value) &&
    Object.keys(value).sort().join('|') === [...expected].sort().join('|');
}

export async function validateArchive(buffer: Buffer): Promise<ArchiveSummary> {
  if (buffer.length < 22 || buffer.length > MAX_ZIP || buffer.readUInt32LE(0) !== 0x04034b50) throw new Error('invalid_archive');
  const zip = await new Promise<yauzl.ZipFile>((resolve, reject) => yauzl.fromBuffer(buffer, { lazyEntries: true, decodeStrings: true, validateEntrySizes: true }, (e, z) => e || !z ? reject(e ?? new Error('invalid_archive')) : resolve(z)));
  const files = new Map<string, Buffer>();
  let expanded = 0;
  try {
    await new Promise<void>((resolve, reject) => {
      zip.on('error', reject);
      zip.on('end', resolve);
      zip.on('entry', entry => {
        const name = entry.fileName;
        if (name.includes('..') || name.startsWith('/') || name.includes('\\') || name.endsWith('/') || files.has(name) || files.size >= 128) return reject(new Error('invalid_archive'));
        if (name !== 'manifest.json' && !/^assets\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.bin$/.test(name)) return reject(new Error('invalid_archive'));
        if ((entry.uncompressedSize ?? 0) > 50 * 1024 * 1024) return reject(new Error('invalid_archive'));
        expanded += entry.uncompressedSize ?? 0;
        if (expanded > MAX_EXPANDED || (name === 'manifest.json' && (entry.uncompressedSize ?? 0) > MAX_MANIFEST)) return reject(new Error('invalid_archive'));
        zip.openReadStream(entry, (error, stream) => {
          if (error || !stream) return reject(error ?? new Error('invalid_archive'));
          const chunks: Buffer[] = []; let size = 0;
          stream.on('data', chunk => { size += chunk.length; if (size > (name === 'manifest.json' ? MAX_MANIFEST : 50 * 1024 * 1024)) stream.destroy(new Error('invalid_archive')); else chunks.push(Buffer.from(chunk)); });
          stream.on('error', reject);
          stream.on('end', () => { files.set(name, Buffer.concat(chunks)); zip.readEntry(); });
        });
      });
      zip.readEntry();
    });
  } finally { zip.close(); }
  const manifestBytes = files.get('manifest.json');
  if (!manifestBytes) throw new Error('invalid_archive');
  const manifest = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(manifestBytes));
  if (!hasKeys(manifest, ['format', 'formatVersion', 'title', 'scoringMode', 'progressionMode', 'startStationKey', 'transitions', 'stations']) ||
      manifest.format !== 'qrhry' || manifest.formatVersion !== 4 || typeof manifest.title !== 'string' || !manifest.title.trim() || manifest.title.length > 120) throw new Error('invalid_archive');
  if (!Array.isArray(manifest.stations) || manifest.stations.length < 2 || manifest.stations.length > 64) throw new Error('invalid_archive');
  let taskCount = 0; let mediaCount = 0; const stationKeys = new Set<string>(); const expectedAssets = new Set<string>();
  for (const [position, station] of manifest.stations.entries()) {
    if (!hasKeys(station, ['title', 'bodyText', 'position', 'stationKey', 'media', 'tasks']) || station.position !== position || typeof station.title !== 'string' || !station.title.trim() || station.title.length > MAX_MANIFEST || typeof station.bodyText !== 'string' || station.bodyText.length > MAX_MANIFEST || typeof station.stationKey !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(station.stationKey) || stationKeys.has(station.stationKey)) throw new Error('invalid_archive');
    stationKeys.add(station.stationKey);
    if (!Array.isArray(station.tasks) || station.tasks.length > 32 || !Array.isArray(station.media) || station.media.length > 2) throw new Error('invalid_archive');
    if (station.media.filter((item: any) => item.mediaType === 'IMAGE').length > 1 || station.media.filter((item: any) => item.mediaType === 'AUDIO').length > 1) throw new Error('invalid_archive');
    taskCount += station.tasks.length;
    for (const [i, task] of station.tasks.entries()) {
      const taskKeys = task.type === 'MULTIPLE_CHOICE'
        ? ['prompt', 'position', 'points', 'timeLimitSeconds', 'type', 'options']
        : task.type === 'TRUE_FALSE'
        ? ['prompt', 'position', 'points', 'timeLimitSeconds', 'type', 'correctAnswer']
          : task.type === 'SHORT_TEXT'
            ? ['prompt', 'position', 'points', 'timeLimitSeconds', 'type', 'correctAnswer'] : [];
      if (!taskKeys.length || !hasKeys(task, taskKeys)) throw new Error('invalid_archive');
      if (task.position !== i || !['MULTIPLE_CHOICE', 'TRUE_FALSE', 'SHORT_TEXT'].includes(task.type) || typeof task.prompt !== 'string' || !task.prompt.trim() || task.prompt.length > MAX_MANIFEST || !Number.isInteger(task.points) || task.points < 0 || task.points > 2147483647 || (task.timeLimitSeconds != null && (!Number.isInteger(task.timeLimitSeconds) || task.timeLimitSeconds < 10 || task.timeLimitSeconds > 1800))) throw new Error('invalid_archive');
      if (task.type === 'MULTIPLE_CHOICE' && (!Array.isArray(task.options) || task.options.length < 2 || task.options.length > 32 || task.options.filter((x: any, n: number) => hasKeys(x, ['text', 'position', 'isCorrect']) && x.position === n && typeof x.text === 'string' && !!x.text.trim() && x.text.length <= MAX_MANIFEST && typeof x.isCorrect === 'boolean').length !== task.options.length || task.options.filter((x: any) => x.isCorrect === true).length !== 1)) throw new Error('invalid_archive');
      if (task.type === 'TRUE_FALSE' && typeof task.correctAnswer !== 'boolean') throw new Error('invalid_archive');
      if (task.type === 'SHORT_TEXT' && (typeof task.correctAnswer !== 'string' || !task.correctAnswer.trim() || task.correctAnswer.length > MAX_MANIFEST)) throw new Error('invalid_archive');
    }
    for (const media of station.media) {
      if (!hasKeys(media, ['entryPath', 'mediaType', 'mimeType', 'originalFilename', 'byteSize', 'sha256']) || typeof media.entryPath !== 'string' || !/^assets\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.bin$/.test(media.entryPath) || expectedAssets.has(media.entryPath) || !['IMAGE', 'AUDIO'].includes(media.mediaType) || typeof media.mimeType !== 'string' || media.mimeType.length > 128 || (media.originalFilename !== null && (typeof media.originalFilename !== 'string' || media.originalFilename.length > 255)) || !Number.isInteger(media.byteSize) || media.byteSize <= 0 || typeof media.sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(media.sha256)) throw new Error('invalid_archive');
      if (!/^(image|audio)\/[A-Za-z0-9!#$&^_.+-]{1,100}$/.test(media.mimeType) || !media.mimeType.startsWith(media.mediaType === 'IMAGE' ? 'image/' : 'audio/')) throw new Error('invalid_archive');
      const bytes = files.get(media.entryPath);
      if (!bytes || bytes.length !== media.byteSize || createHash('sha256').update(bytes).digest('hex') !== media.sha256) throw new Error('invalid_archive');
      expectedAssets.add(media.entryPath); mediaCount++;
    }
  }
  if (files.size !== expectedAssets.size + 1 || taskCount > 1024) throw new Error('invalid_archive');
  if (!Array.isArray(manifest.transitions) || !['LINEAR', 'BRANCHED'].includes(manifest.progressionMode) || !['FIRST_TRY_ONLY', 'EVENTUAL_CORRECT'].includes(manifest.scoringMode)) throw new Error('invalid_archive');
  if (manifest.progressionMode === 'LINEAR' && (manifest.startStationKey !== null || manifest.transitions.length !== 0)) throw new Error('invalid_archive');
  if (manifest.progressionMode === 'BRANCHED') {
    if (!stationKeys.has(manifest.startStationKey) || !Array.isArray(manifest.transitions)) throw new Error('invalid_archive');
    if (manifest.stations.some((station: any) => station.tasks.length > 1)) throw new Error('invalid_archive');
    const seen = new Set<string>();
    const requiredEdgeCount = manifest.stations.reduce((count: number, station: any) => count + (station.tasks.length ? 2 : 1), 0);
    if (manifest.transitions.length !== requiredEdgeCount) throw new Error('invalid_archive');
    for (const edge of manifest.transitions) {
      if (!hasKeys(edge, ['sourceStationKey', 'condition', 'targetStationKey'])) throw new Error('invalid_archive');
      const sourceStation = manifest.stations.find((item: any) => item.stationKey === edge.sourceStationKey);
      if (!sourceStation || (sourceStation.tasks.length ? !['CORRECT', 'INCORRECT'].includes(edge.condition) : edge.condition !== 'INFO')) throw new Error('invalid_archive');
      const key = `${edge.sourceStationKey}:${edge.condition}`;
      if (!stationKeys.has(edge.sourceStationKey) || (edge.targetStationKey != null && !stationKeys.has(edge.targetStationKey)) || seen.has(key) || !['CORRECT', 'INCORRECT', 'INFO'].includes(edge.condition)) throw new Error('invalid_archive');
      seen.add(key);
    }
    for (const station of manifest.stations) for (const condition of station.tasks.length ? ['CORRECT', 'INCORRECT'] : ['INFO']) if (!seen.has(`${station.stationKey}:${condition}`)) throw new Error('invalid_archive');
    const outgoing = new Map<string, string[]>();
    for (const edge of manifest.transitions) if (edge.targetStationKey != null) outgoing.set(edge.sourceStationKey, [...(outgoing.get(edge.sourceStationKey) ?? []), edge.targetStationKey]);
    const visiting = new Set<string>(); const visited = new Set<string>();
    const walk = (key: string): void => {
      if (visiting.has(key)) throw new Error('invalid_archive');
      if (visited.has(key)) return;
      visiting.add(key); for (const next of outgoing.get(key) ?? []) walk(next);
      visiting.delete(key); visited.add(key);
    };
    walk(manifest.startStationKey);
    if (visited.size !== stationKeys.size) throw new Error('invalid_archive');
  }
  return { title: manifest.title.trim(), stationCount: manifest.stations.length, taskCount, mediaCount, sha256: createHash('sha256').update(buffer).digest('hex'), size: buffer.length };
}
