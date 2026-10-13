package com.ifallhub.app;

import android.net.Uri;
import android.content.Intent;
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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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
            transcodeOutput = new File(getContext().getCacheDir(),
                    "ifallhub_transcode_" + System.currentTimeMillis() + ".mp4");
            streamServer = new LocalStreamServer(transcodeOutput);
            int port = streamServer.start();

            String[] args = buildTranscodeArgs(inputUrl, call.getObject("headers"), transcodeOutput);
            FFmpegSession session = FFmpegKit.executeWithArgumentsAsync(args, finished -> {
                Log.i(TAG, "transcode finished rc=" + finished.getReturnCode());
            });
            activeSession.set(session.getSessionId());

            JSObject ret = new JSObject();
            ret.put("playbackUrl", streamServer.getPlaybackUrl());
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

    private String[] buildTranscodeArgs(String inputUrl, JSObject headers, File output) {
        List<String> args = new ArrayList<>();
        args.add("-y");
        args.add("-hide_banner");

        // Extra request headers for the source (signed CDN links often need
        // cookies/referers).
        if (headers != null) {
            StringBuilder headerBlock = new StringBuilder();
            Iterator<String> keys = headers.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                // opt() avoids JSONException from malformed bridge payloads.
                Object value = headers.opt(key);
                if (value != null && value != JSONObject.NULL) {
                    headerBlock.append(key).append(": ").append(String.valueOf(value)).append("\r\n");
                }
            }
            if (headerBlock.length() > 0) {
                args.add("-headers");
                args.add(headerBlock.toString());
            }
        }

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
