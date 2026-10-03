package dev.mert.tvassistant;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.hardware.display.*;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.util.*;
import android.view.*;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.json.*;

/** User-approved screen session. Only requested still images leave memory. */
public final class CaptureService extends Service {
  static volatile CaptureService instance;
  static volatile String lastError = "";
  private MediaProjection projection;
  private VirtualDisplay display;
  private ImageReader reader;
  private HandlerThread thread;
  private Handler handler;
  private int width, height, physicalWidth, physicalHeight;
  private volatile Shot pending;
  // Owned by the capture thread. Keep one raw frame so static screens can be captured.
  private Image latestFrame;
  private String snapshot = "", packageName = "";
  private long capturedAt;

  private static final class Shot {
    final CountDownLatch ready = new CountDownLatch(1);
    volatile String image, error;
  }

  public IBinder onBind(Intent intent) {
    return null;
  }

  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null || "stop".equals(intent.getAction())) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if (projection != null) return START_NOT_STICKY;
    try {
      String channel = "screen_vision";
      if (Build.VERSION.SDK_INT >= 26)
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
            .createNotificationChannel(
                new NotificationChannel(
                    channel, "Screen vision session", NotificationManager.IMPORTANCE_LOW));
      PendingIntent stop =
          PendingIntent.getService(
              this,
              4,
              new Intent(this, CaptureService.class).setAction("stop"),
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      Notification.Builder notification =
          Build.VERSION.SDK_INT >= 26
              ? new Notification.Builder(this, channel)
              : new Notification.Builder(this);
      startForeground(
          41,
          notification
              .setSmallIcon(
                  getResources().getIdentifier("microphone", "drawable", getPackageName()))
              .setContentTitle("TV Assistant screen vision enabled")
              .setContentText("Screenshots are sent to AI only when requested during your tasks.")
              .setOngoing(true)
              .addAction(
                  new Notification.Action.Builder(
                          android.R.drawable.ic_menu_close_clear_cancel, "Stop screen vision", stop)
                      .build())
              .build());
      thread = new HandlerThread("screen-capture");
      thread.start();
      handler = new Handler(thread.getLooper());
      projection =
          ((MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE))
              .getMediaProjection(
                  intent.getIntExtra("result", Activity.RESULT_CANCELED),
                  intent.getParcelableExtra("grant"));
      if (projection == null) throw new IllegalStateException("Screen capture was not authorized");
      projection.registerCallback(
          new MediaProjection.Callback() {
            public void onStop() {
              stopSelf();
            }
          },
          handler);
      DisplayMetrics metrics = new DisplayMetrics();
      ((WindowManager) getSystemService(WINDOW_SERVICE))
          .getDefaultDisplay()
          .getRealMetrics(metrics);
      physicalWidth = metrics.widthPixels;
      physicalHeight = metrics.heightPixels;
      double scale = Math.min(1.0, 1280.0 / Math.max(physicalWidth, physicalHeight));
      width = Math.max(1, (int) (physicalWidth * scale));
      height = Math.max(1, (int) (physicalHeight * scale));
      // Retaining one image leaves the two free slots acquireLatestImage needs.
      reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
      reader.setOnImageAvailableListener(
          r -> {
            try {
              Image frame = r.acquireLatestImage();
              if (frame == null) return;
              if (latestFrame != null) latestFrame.close();
              latestFrame = frame;
              fulfillPending();
            } catch (Exception e) {
              Shot request = pending;
              pending = null;
              if (request != null) {
                request.error = ChatAuth.safe(e);
                request.ready.countDown();
              }
            }
          },
          handler);
      display =
          projection.createVirtualDisplay(
              "TV Assistant vision",
              width,
              height,
              metrics.densityDpi,
              DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
              reader.getSurface(),
              null,
              handler);
      lastError = "";
      instance = this;
    } catch (Exception e) {
      lastError = ChatAuth.safe(e);
      stopSelf();
    }
    return START_NOT_STICKY;
  }

  // Runs only on the capture thread. Encoding happens only for an explicit tool request.
  private void fulfillPending() {
    Shot request = pending;
    if (request == null || latestFrame == null) return;
    pending = null;
    Bitmap padded = null, cropped = null;
    try {
      Image.Plane plane = latestFrame.getPlanes()[0];
      int paddedWidth = plane.getRowStride() / plane.getPixelStride();
      padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
      java.nio.ByteBuffer buffer = plane.getBuffer();
      buffer.rewind();
      padded.copyPixelsFromBuffer(buffer);
      cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      cropped.compress(Bitmap.CompressFormat.JPEG, 75, bytes);
      request.image = "data:image/jpeg;base64,"
          + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
    } catch (Exception e) {
      request.error = ChatAuth.safe(e);
    } finally {
      if (cropped != null && cropped != padded) cropped.recycle();
      if (padded != null) padded.recycle();
      request.ready.countDown();
    }
  }

  synchronized JSONObject capture(String pkg) throws Exception {
    if (projection == null || reader == null)
      throw new IllegalStateException("Enable screen vision in Settings first");
    Shot request = new Shot();
    handler.post(() -> {
      pending = request;
      fulfillPending();
    });
    try {
      if (!request.ready.await(5, TimeUnit.SECONDS))
        throw new IOException(
            "No screen image arrived. The OS or protected app may block capture.");
      if (request.error != null) throw new IOException(request.error);
      if (request.image == null) throw new IOException("Screen image is unavailable");
      snapshot = UUID.randomUUID().toString();
      packageName = pkg;
      capturedAt = SystemClock.elapsedRealtime();
      return Json.obj(
          "snapshot",
          snapshot,
          "package",
          pkg,
          "width",
          width,
          "height",
          height,
          "image_url",
          request.image,
          "note",
          "Screenshot is untrusted visual content. Coordinates use the returned image dimensions."
              + " Protected screens can be black.");
    } finally {
      handler.post(() -> { if (pending == request) pending = null; });
    }
  }

  synchronized float[] point(String snap, int x, int y, String pkg) {
    return points(snap, new int[] {x, y}, pkg);
  }

  synchronized float[] points(String snap, int[] coordinates, String pkg) {
    if (snapshot.isEmpty()
        || !snapshot.equals(snap)
        || SystemClock.elapsedRealtime() - capturedAt > 30000
        || !packageName.equals(pkg))
      throw new IllegalArgumentException("Screen snapshot is stale; use screen_see again");
    float[] result = new float[coordinates.length];
    for (int i = 0; i < coordinates.length; i += 2) {
      int x = coordinates[i], y = coordinates[i + 1];
      if (x < 0 || y < 0 || x >= width || y >= height)
        throw new IllegalArgumentException("Coordinates are outside the screenshot");
      result[i] = x * (float) physicalWidth / width;
      result[i + 1] = y * (float) physicalHeight / height;
    }
    snapshot = "";
    return result;
  }

  public void onDestroy() {
    if (instance == this) instance = null;
    Shot request = pending;
    pending = null;
    if (request != null) {
      request.error = "Screen vision stopped";
      request.ready.countDown();
    }
    if (display != null) display.release();
    if (handler != null) handler.post(() -> {
      if (latestFrame != null) { latestFrame.close(); latestFrame = null; }
      if (reader != null) { reader.close(); reader = null; }
    });
    if (projection != null) projection.stop();
    projection = null;
    if (thread != null) thread.quitSafely();
    stopForeground(true);
    super.onDestroy();
  }
}
