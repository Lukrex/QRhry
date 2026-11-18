import assert from 'node:assert/strict';
import { test } from 'node:test';
import { validateArchive } from './archiveValidator';

function crc32(data: Buffer): number {
  let crc = 0xffffffff;
  for (const byte of data) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function zip(entries: Array<[string, Buffer]>): Buffer {
  const locals: Buffer[] = []; const centrals: Buffer[] = []; let offset = 0;
  for (const [name, data] of entries) {
    const nameBytes = Buffer.from(name); const crc = crc32(data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(20, 4); local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(data.length, 18); local.writeUInt32LE(data.length, 22); local.writeUInt16LE(nameBytes.length, 26);
    locals.push(local, nameBytes, data);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6);
    central.writeUInt32LE(crc, 16); central.writeUInt32LE(data.length, 20); central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(nameBytes.length, 28); central.writeUInt32LE(offset, 42);
    centrals.push(central, nameBytes);
    offset += local.length + nameBytes.length + data.length;
  }
  const centralBytes = Buffer.concat(centrals); const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(entries.length, 8); end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(centralBytes.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, centralBytes, end]);
}

const base: any = {
  format: 'qrhry', formatVersion: 4, title: 'Trail', scoringMode: 'FIRST_TRY_ONLY',
  progressionMode: 'LINEAR', startStationKey: null, transitions: [],
  stations: [
    { title: 'Start', bodyText: '', position: 0, stationKey: '550e8400-e29b-41d4-a716-446655440000', media: [], tasks: [] },
    { title: 'Finish', bodyText: '', position: 1, stationKey: '6ba7b810-9dad-11d1-80b4-00c04fd430c8', media: [], tasks: [] }
  ]
};

test('accepts a bounded valid v4 game archive', async () => {
  const summary = await validateArchive(zip([['manifest.json', Buffer.from(JSON.stringify(base))]]));
  assert.equal(summary.title, 'Trail'); assert.equal(summary.stationCount, 2); assert.equal(summary.taskCount, 0);
});

test('rejects unsupported format versions and unexpected manifest fields', async () => {
  await assert.rejects(validateArchive(zip([['manifest.json', Buffer.from(JSON.stringify({ ...base, formatVersion: 5 }))]])));
  await assert.rejects(validateArchive(zip([['manifest.json', Buffer.from(JSON.stringify({ ...base, injected: true }))]])));
});

test('rejects unsafe zip paths and task timers outside the supported range', async () => {
  await assert.rejects(validateArchive(zip([
    ['manifest.json', Buffer.from(JSON.stringify(base))], ['../escape', Buffer.from('bad')]
  ])));
  const timed = structuredClone(base);
  timed.stations[0].tasks = [{ prompt: 'Question', position: 0, points: 10, timeLimitSeconds: 1, type: 'TRUE_FALSE', correctAnswer: true }];
  await assert.rejects(validateArchive(zip([['manifest.json', Buffer.from(JSON.stringify(timed))]])));
});
