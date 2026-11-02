package com.hongguoskip;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

/**
 * 诊断日志（对应原版 Diag）：内存环形缓冲 400 行 + 文件日志
 * （getExternalFilesDir/diag.log，超 128KB 截断保留最近一半、3 天过期清空）。
 * 主界面一键复制，供远程调试；每 1.2s 一帧判定快照，格式尽量兼容原版便于对照。
 */
public final class Diag {

    public static final int MAX_LINES = 400;
    private static final long MAX_FILE_BYTES = 128 * 1024;
    private static final long EXPIRE_MS = 3L * 24 * 3600 * 1000;

    private static final ArrayDeque<String> BUFFER = new ArrayDeque<>();
    private static final SimpleDateFormat TS = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static File logFile;

    private Diag() {
    }

    /** 初始化日志文件（幂等；过期清理）。MainActivity / Service 启动时各调一次。 */
    public static synchronized void init(Context c) {
        if (logFile != null) {
            return;
        }
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir == null) {
                dir = c.getFilesDir();
            }
            File f = new File(dir, "diag.log");
            if (f.exists() && System.currentTimeMillis() - f.lastModified() > EXPIRE_MS) {
                f.delete();
            }
            logFile = f;
        } catch (Throwable t) {
            logFile = null;
        }
    }

    public static synchronized void log(String tag, String msg) {
        String line = TS.format(new Date()) + " " + tag + ": " + msg;
        if (BUFFER.size() >= MAX_LINES) {
            BUFFER.pollFirst();
        }
        BUFFER.addLast(line);
        appendFile(line);
    }

    /** 导出环形缓冲全部内容（复制诊断日志用） */
    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (Iterator<String> it = BUFFER.iterator(); it.hasNext(); ) {
            sb.append(it.next()).append('\n');
        }
        return sb.toString();
    }

    public static synchronized void clear() {
        BUFFER.clear();
        File f = logFile;
        if (f != null && f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static void appendFile(String line) {
        File f = logFile;
        if (f == null) {
            return;
        }
        try {
            if (f.length() > MAX_FILE_BYTES) {
                truncateKeepRecent(f, MAX_FILE_BYTES / 2);
            }
            try (FileOutputStream fos = new FileOutputStream(f, true)) {
                fos.write((line + "\n").getBytes("UTF-8"));
            }
        } catch (IOException ignored) {
        }
    }

    /** 超限时截断：保留最近一半字节（旧日志从头丢弃） */
    private static void truncateKeepRecent(File f, long keepBytes) {
        try {
            byte[] all = Files.readAllBytes(f.toPath());
            int keep = (int) Math.min(keepBytes, all.length);
            int start = all.length - keep;
            try (FileOutputStream fos = new FileOutputStream(f, false)) {
                fos.write(all, start, keep);
            }
        } catch (IOException ignored) {
        }
    }
}
