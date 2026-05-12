package com.jopsis.httpaceserveproxy;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class AssetCopier {
    private AssetCopier() {
    }

    static void copyTree(Context context, String assetPath, File destination) throws IOException {
        AssetManager assets = context.getAssets();
        String[] children = assets.list(assetPath);
        if (children == null || children.length == 0) {
            copyFile(context, assetPath, destination);
            return;
        }
        if (!destination.exists() && !destination.mkdirs()) {
            throw new IOException("Cannot create " + destination);
        }
        for (String child : children) {
            copyTree(context, assetPath + "/" + child, new File(destination, child));
        }
    }

    static void copyFile(Context context, String assetPath, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create " + parent);
        }
        try (InputStream in = context.getAssets().open(assetPath);
             FileOutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
        }
    }
}

