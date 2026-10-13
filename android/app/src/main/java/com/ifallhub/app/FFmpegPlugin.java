package com.ifallhub.app;

import android.net.Uri;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.app.DownloadManager;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Capacitor bridge to FFmpegKitNext (com.arthenica:ffmpeg-kit-next).
 *
 * <p>Powers H.265/HEVC and .mkv playback for the Ifallhub app: {@link
 * #startTranscode} live-transcodes an unsupported stream to H.264/AAC and
 * serves it over a loopback HTTP endpoint the WebView &lt;video&gt; element
 * can play, while the whole JS player overlay keeps working unchanged.</p>
 */
@CapacitorPlugin(name = "FFmpeg")
public class FFmpegPlugin extends Plugin {

    private static final String TAG = "IfallhubFFmpeg";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicLong activeSession = new AtomicLong(0L);
    private volatile LocalStreamServer streamServer;
    private volatile File transcodeOutput;

    // --- Diagnostics -------------------------------------------------------

    @PluginMethod
    public void getVersion(PluginCall call) {
        JSObject ret = new JSObject();
        try {
            ret.put("version", FFmpegKitConfig.getFFmpegVersion());
            ret.put("buildConfig", FFmpegKitConfig.getVersion());
            call.resolve(ret);
        } catch (Exception error) {
            call.reject("Unable to read the FFmpegKit version: " + error.getMessage(), error);
        }
    }

    /** Runs arbitrary ffmpeg arguments synchronously on a worker thread. */
    @PluginMethod
    public void execute(PluginCall call) {
        List<String> args = toStringList(call.getArray("args"));
        if (args.isEmpty()) {
            call.reject("execute() needs a non-empty args array");
            return;
        }
        executor.execute(() -> {
            FFmpegSession session = FFmpegKit.executeWithArguments(args.toArray(new String[0]));
            String output = session.getAllLogsAsString();
            if (output.length() > 16_384) output = output.substring(0, 16_384);
            JSObject ret = new JSObject();
            ret.put("success", ReturnCode.isSuccess(session.getReturnCode()));
            ret.put("returnCode", session.getReturnCode() != null
                    ? session.getReturnCode().getValue() : -1);
            ret.put("output", output);
            call.resolve(ret);
        });
    }

    // --- Live H.265 → H.264 transcode ------------------------------------

    /**
     * Starts a live transcode and returns the loopback playback URL.
     *
     * <p>Options: {@code inputUrl} (required) and {@code headers}
     * (optional map of extra HTTP request headers for the source).</p>
     */
    @PluginMethod
    public void startTranscode(PluginCall call) {
        String inputUrl = call.getString("inputUrl");
        if (inputUrl == null || inputUrl.trim().isEmpty()) {
            call.reject("startTranscode() needs an inputUrl");
            return;
        }

        // Only one transcode at a time.
        stopTranscodeInternal();

        try {
            File output = new File(getContext().getCacheDir(),
                    "ifallhub_transcode_" + System.currentTimeMillis() + ".mp4");
            transcodeOutput = output;
            LocalStreamServer server = new LocalStreamServer(output);
            streamServer = server;
            int port = server.start();

            String[] args = buildTranscodeArgs(inputUrl, call.getObject("headers"), output);
            FFmpegSession session = FFmpegKit.executeWithArgumentsAsync(args, finished -> {
                Log.i(TAG, "transcode finished rc=" + finished.getReturnCode());
                if (streamServer == server) server.finish();
            });
            activeSession.set(session.getSessionId());

            JSObject ret = new JSObject();
            ret.put("playbackUrl", server.getPlaybackUrl());
            ret.put("port", port);
            call.resolve(ret);
        } catch (Exception error) {
            Log.e(TAG, "startTranscode failed", error);
            stopTranscodeInternal();
            call.reject("Could not start the FFmpegKit transcode: " + error.getMessage(), error);
        }
    }

    @PluginMethod
    public void stopTranscode(PluginCall call) {
        boolean stopped = stopTranscodeInternal();
        JSObject ret = new JSObject();
        ret.put("stopped", stopped);
        call.resolve(ret);
    }

    @PluginMethod
    public void isTranscoding(PluginCall call) {
        LocalStreamServer server = streamServer;
        JSObject ret = new JSObject();
        ret.put("active", server != null && server.isRunning());
        ret.put("playbackUrl", server != null ? server.getPlaybackUrl() : "");
        call.resolve(ret);
    }

    /** Queue a direct file in Android's DownloadManager. */
    @PluginMethod
    public void downloadFile(PluginCall call) {
        String url = call.getString("url");
        if (!isHttpUrl(url)) {
            call.reject("downloadFile() needs an http(s) URL");
            return;
        }

        String fileName = sanitizeFileName(call.getString("fileName"));
        try {
            DownloadManager manager = (DownloadManager) getContext()
                    .getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) {
                call.reject("Android's DownloadManager is unavailable");
                return;
            }

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle(fileName);
            request.setDescription("Ifallhub video download");
            request.setMimeType(mimeTypeFor(fileName));
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, "Ifallhub/" + fileName);
            } else {
                // App-specific Downloads needs no storage permission on Android 8/9.
                request.setDestinationInExternalFilesDir(
                        getContext(), Environment.DIRECTORY_DOWNLOADS, "Ifallhub/" + fileName);
            }

            long downloadId = manager.enqueue(request);
            JSObject ret = new JSObject();
            ret.put("ok", true);
            ret.put("downloadId", downloadId);
            ret.put("fileName", fileName);
            call.resolve(ret);
        } catch (Exception error) {
            Log.e(TAG, "downloadFile failed", error);
            call.reject("Could not start the Android download: " + error.getMessage(), error);
        }
    }

    /** Download and mux segmented DASH/HLS media into one MKV in Downloads. */
    @PluginMethod
    public void downloadStream(PluginCall call) {
        String inputUrl = call.getString("inputUrl");
        if (!isHttpUrl(inputUrl)) {
            call.reject("downloadStream() needs an http(s) inputUrl");
            return;
        }

        String fileName = ensureMatroskaExtension(call.getString("fileName"));
        File output = new File(getContext().getCacheDir(),
                "ifallhub_download_" + System.currentTimeMillis() + ".mkv");
        String[] args = buildDownloadArgs(inputUrl, call.getObject("headers"), output);
        try {
            FFmpegKit.executeWithArgumentsAsync(args, session -> {
                try {
                    if (!ReturnCode.isSuccess(session.getReturnCode())
                            || !output.isFile() || output.length() == 0L) {
                        int code = session.getReturnCode() != null
                                ? session.getReturnCode().getValue() : -1;
                        call.reject("FFmpeg could not package this DASH/HLS stream (exit " + code
                                + "). The link may have expired or the stream may be DRM-protected.");
                        return;
                    }

                    long size = output.length();
                    String savedUri = publishDownload(output, fileName);
                    JSObject ret = new JSObject();
                    ret.put("success", true);
                    ret.put("fileName", fileName);
                    ret.put("uri", savedUri);
                    ret.put("sizeBytes", size);
                    call.resolve(ret);
                } catch (Exception error) {
                    Log.e(TAG, "downloadStream save failed", error);
                    call.reject("The stream was fetched but could not be saved to Downloads: "
                            + error.getMessage(), error);
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    output.delete();
                }
            });
        } catch (Exception error) {
            //noinspection ResultOfMethodCallIgnored
            output.delete();
            Log.e(TAG, "downloadStream failed to start", error);
            call.reject("Could not start FFmpeg DASH/HLS download: " + error.getMessage(), error);
        }
    }

    /** Opens a URL with the system handler (VLC / browser / downloader). */
    @PluginMethod
    public void openExternal(PluginCall call) {
        String url = call.getString("url");
        if (url == null || url.trim().isEmpty()) {
            call.reject("openExternal() needs a url");
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            JSObject ret = new JSObject();
            ret.put("ok", true);
            call.resolve(ret);
        } catch (Exception error) {
            JSObject ret = new JSObject();
            ret.put("ok", false);
            ret.put("error", String.valueOf(error.getMessage()));
            call.resolve(ret);
        }
    }

    // --- Internals --------------------------------------------------------

    private boolean isHttpUrl(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        String scheme = Uri.parse(value.trim()).getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private String sanitizeFileName(String value) {
        String name = value == null ? "" : value.trim();
        name = name.replaceAll("[\\\\/:*?\\\"<>|\\p{Cntrl}]", "_")
                .replaceAll("\\s+", " ")
                .replaceAll("^\\.+", "")
                .trim();
        if (name.isEmpty()) name = "ifallhub-video";
        if (name.length() > 180) name = name.substring(0, 180).trim();
        return name;
    }

    private String ensureMatroskaExtension(String value) {
        String name = sanitizeFileName(value);
        if (!name.toLowerCase(Locale.ROOT).endsWith(".mkv")) {
            name = name.replaceAll("(?i)\\.(mp4|m4v|mkv|avi|webm|mov|ts|mpd|m3u8|m3u)$", "");
            if (name.isEmpty()) name = "ifallhub-video";
            name += ".mkv";
        }
        return name;
    }

    private String mimeTypeFor(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp4") || lower.endsWith(".m4v")) return "video/mp4";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".webm")) return "video/webm";
        return "application/octet-stream";
    }

    private String publishDownload(File source, String fileName) throws IOException {
        Context context = getContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "video/x-matroska");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/Ifallhub");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri destination = null;
            try {
                destination = context.getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (destination == null) throw new IOException("Android could not create a Downloads entry");
                try (InputStream input = new FileInputStream(source);
                     OutputStream output = context.getContentResolver().openOutputStream(destination, "w")) {
                    if (output == null) throw new IOException("Android could not open the Downloads file");
                    copy(input, output);
                }

                ContentValues ready = new ContentValues();
                ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                context.getContentResolver().update(destination, ready, null, null);
                return destination.toString();
            } catch (IOException error) {
                if (destination != null) context.getContentResolver().delete(destination, null, null);
                throw error;
            } catch (RuntimeException error) {
                if (destination != null) context.getContentResolver().delete(destination, null, null);
                throw error;
            }
        }

        // Android 8/9 cannot insert into MediaStore.Downloads. Save to the
        // app-specific Download folder (still visible through device storage).
        File external = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File directory = new File(external != null ? external : context.getFilesDir(), "Ifallhub");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create the app Downloads folder");
        }
        File destination = new File(directory, fileName);
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(destination, false)) {
            copy(input, output);
        }
        return destination.getAbsolutePath();
    }

    private void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        output.flush();
    }

    private void appendHeaders(List<String> args, JSObject headers) {
        if (headers == null) return;
        StringBuilder headerBlock = new StringBuilder();
        Iterator<String> keys = headers.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = headers.opt(key);
            if (value == null || value == JSONObject.NULL
                    || !key.matches("[A-Za-z0-9-]+")) continue;
            String cleanValue = String.valueOf(value).replace("\r", "").replace("\n", "");
            headerBlock.append(key).append(": ").append(cleanValue).append("\r\n");
        }
        if (headerBlock.length() > 0) {
            args.add("-headers");
            args.add(headerBlock.toString());
        }
    }

    private String[] buildDownloadArgs(String inputUrl, JSObject headers, File output) {
        List<String> args = new ArrayList<>();
        args.add("-y");
        args.add("-hide_banner");
        args.add("-rw_timeout");
        args.add("30000000");
        appendHeaders(args, headers);
        args.add("-i");
        args.add(inputUrl);
        args.add("-map");
        args.add("0:v:0?");
        args.add("-map");
        args.add("0:a:0?");
        args.add("-c");
        args.add("copy");
        args.add("-max_muxing_queue_size");
        args.add("2048");
        args.add("-f");
        args.add("matroska");
        args.add(output.getAbsolutePath());
        return args.toArray(new String[0]);
    }

    private String[] buildTranscodeArgs(String inputUrl, JSObject headers, File output) {
        List<String> args = new ArrayList<>();
        args.add("-y");
        args.add("-hide_banner");

        // Extra request headers for the source (signed CDN links often need
        // cookies/referers); names and values are sanitized by the helper.
        appendHeaders(args, headers);

        // Ignore long network stalls on flaky mirrors, then live-encode.
        args.add("-rw_timeout");
        args.add("15000000");
        args.add("-i");
        args.add(inputUrl);

        args.add("-map");
        args.add("0:v:0");
        args.add("-map");
        args.add("0:a:0?");

        args.add("-c:v");
        args.add("libx264");
        args.add("-preset");
        args.add("ultrafast");
        args.add("-tune");
        args.add("zerolatency");
        args.add("-crf");
        args.add("23");
        args.add("-pix_fmt");
        args.add("yuv420p");
        args.add("-max_muxing_queue_size");
        args.add("1024");

        args.add("-c:a");
        args.add("aac");
        args.add("-ac");
        args.add("2");
        args.add("-b:a");
        args.add("128k");

        // Fragmented MP4 with the moov header first so the WebView can start
        // playback while the file is still growing.
        args.add("-f");
        args.add("mp4");
        args.add("-movflags");
        args.add("frag_keyframe+empty_moov+default_base_moof");
        args.add("-flush_packets");
        args.add("1");

        args.add(output.getAbsolutePath());
        return args.toArray(new String[0]);
    }

    private boolean stopTranscodeInternal() {
        boolean stopped = false;
        long session = activeSession.getAndSet(0L);
        if (session != 0L) {
            try {
                FFmpegKit.cancel(session);
                stopped = true;
            } catch (Exception error) {
                Log.w(TAG, "cancel failed", error);
            }
        }
        LocalStreamServer server = streamServer;
        if (server != null) {
            server.stop();
            streamServer = null;
            stopped = true;
        }
        File output = transcodeOutput;
        if (output != null) {
            //noinspection ResultOfMethodCallIgnored
            output.delete();
            transcodeOutput = null;
        }
        return stopped;
    }

    private List<String> toStringList(JSArray array) {
        List<String> out = new ArrayList<>();
        if (array == null) return out;
        try {
            for (int index = 0; index < array.length(); index += 1) {
                Object value = array.get(index);
                if (value != null && value != JSONObject.NULL) out.add(String.valueOf(value));
            }
        } catch (Exception error) {
            Log.w(TAG, "args parse failed", error);
        }
        return out;
    }

    @Override
    protected void handleOnDestroy() {
        stopTranscodeInternal();
        executor.shutdownNow();
        super.handleOnDestroy();
    }
}
