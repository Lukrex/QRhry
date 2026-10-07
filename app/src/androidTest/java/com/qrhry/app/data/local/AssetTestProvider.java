package com.qrhry.app.data.local;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class AssetTestProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "audio".equals(uri.getLastPathSegment()) ? "audio/wav" : "image/png";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        byte[] bytes = "audio".equals(uri.getLastPathSegment()) ? createWaveBytes() : createPngBytes();
        String extension = "audio".equals(uri.getLastPathSegment()) ? ".wav" : ".png";
        File source;
        try {
            source = File.createTempFile("asset-source-", extension, getContext().getCacheDir());
            try (FileOutputStream output = new FileOutputStream(source)) {
                output.write(bytes);
            }
            ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
                    source,
                    ParcelFileDescriptor.MODE_READ_ONLY
            );
            source.delete();
            return descriptor;
        } catch (IOException exception) {
            throw new FileNotFoundException(exception.getMessage());
        }
    }

    private static byte[] createPngBytes() {
        Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.RED);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        bitmap.recycle();
        return output.toByteArray();
    }

    private static byte[] createWaveBytes() {
        int sampleRate = 8000;
        int sampleBytes = sampleRate / 10 * 2;
        ByteBuffer wave = ByteBuffer.allocate(44 + sampleBytes).order(ByteOrder.LITTLE_ENDIAN);
        wave.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        wave.putInt(36 + sampleBytes);
        wave.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        wave.putInt(16);
        wave.putShort((short) 1);
        wave.putShort((short) 1);
        wave.putInt(sampleRate);
        wave.putInt(sampleRate * 2);
        wave.putShort((short) 2);
        wave.putShort((short) 16);
        wave.put("data".getBytes(StandardCharsets.US_ASCII));
        wave.putInt(sampleBytes);
        return wave.array();
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}