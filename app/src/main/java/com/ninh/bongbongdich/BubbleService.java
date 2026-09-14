package com.ninh.bongbongdich;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BubbleService extends Service {

    public static final String ACTION_START = "com.ninh.bongbongdich.START";
    public static final String ACTION_STOP = "com.ninh.bongbongdich.STOP";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String PREFS_NAME = "bubble_state";
    public static final String KEY_RUNNING = "running";

    private static final String CHANNEL_ID = "bubble_translate_channel";
    private static final int NOTIFICATION_ID = 2409;
    private static final int MAX_REGIONS = 24;
    private static final String ONLINE_TRANSLATE_URL =
            "https://translate.googleapis.com/translate_a/single"
                    + "?client=gtx&sl=zh-CN&tl=vi&dt=t";

    private WindowManager windowManager;
    private TextView bubbleView;
    private GradientDrawable bubbleBackground;
    private WindowManager.LayoutParams bubbleParams;

    private FrameLayout translationLayer;
    private WindowManager.LayoutParams translationLayerParams;
    private final List<Rect> placedTranslationBounds = new ArrayList<>();
    private final List<Rect> sourceTranslationBounds = new ArrayList<>();
    private boolean translationsVisible;

    private MediaProjection mediaProjection;
    private MediaProjection.Callback mediaProjectionCallback;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;

    private Handler mainHandler;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private ExecutorService onlineTranslationExecutor;
    private final ConcurrentHashMap<String, String> translationCache =
            new ConcurrentHashMap<>();

    private TextRecognizer textRecognizer;
    private volatile boolean captureNextFrame;
    private volatile boolean busy;
    private volatile boolean cleaningUp;
    private volatile int captureSequence;

    private int screenWidth;
    private int screenHeight;
    private int densityDpi;

    @Override
    public void onCreate() {
        super.onCreate();

        mainHandler = new Handler(Looper.getMainLooper());
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        captureThread = new HandlerThread("screen-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        onlineTranslationExecutor = Executors.newFixedThreadPool(4);

        textRecognizer = TextRecognition.getClient(
                new ChineseTextRecognizerOptions.Builder().build()
        );

        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!ACTION_START.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startAsForeground();

        if (!Settings.canDrawOverlays(this)) {
            showToast("Ứng dụng chưa có quyền hiển thị bong bóng.");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (mediaProjection != null) {
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Integer.MIN_VALUE);
        Intent resultData;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }

        if (resultCode == Integer.MIN_VALUE || resultData == null) {
            showToast("Thiếu quyền chụp màn hình. Hãy mở ứng dụng và bật lại.");
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            startProjection(resultCode, resultData);
        } catch (Exception exception) {
            showToast("Không thể bắt đầu chụp màn hình: " + readableError(exception));
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void startProjection(int resultCode, Intent resultData) {
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            throw new IllegalStateException("Không tìm thấy MediaProjectionManager");
        }

        mediaProjection = manager.getMediaProjection(resultCode, resultData);
        if (mediaProjection == null) {
            throw new IllegalStateException("Không nhận được quyền chia sẻ màn hình");
        }

        mediaProjectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                if (!cleaningUp) {
                    mainHandler.post(() -> {
                        showToast("Chia sẻ màn hình đã dừng.");
                        stopSelf();
                    });
                }
            }

            @Override
            public void onCapturedContentResize(int width, int height) {
                if (cleaningUp || width <= 0 || height <= 0) {
                    return;
                }
                mainHandler.post(() -> resizeProjectionTo(width, height));
            }
        };
        mediaProjection.registerCallback(mediaProjectionCallback, mainHandler);

        updateScreenBounds();
        imageReader = createImageReader(screenWidth, screenHeight);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "BongBongDichCapture",
                screenWidth,
                screenHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                captureHandler
        );

        if (virtualDisplay == null) {
            throw new IllegalStateException("Không tạo được màn hình ảo");
        }

        createTranslationLayer();
        createBubble();

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_RUNNING, true)
                .apply();

        showToast("Chạm 译 để dịch online, chạm × để tắt bản dịch.");
    }

    private ImageReader createImageReader(int width, int height) {
        ImageReader reader = ImageReader.newInstance(
                Math.max(width, 1),
                Math.max(height, 1),
                PixelFormat.RGBA_8888,
                2
        );
        reader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);
        return reader;
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null || !captureNextFrame || cleaningUp) {
                return;
            }

            captureNextFrame = false;
            Bitmap bitmap = imageToBitmap(image);
            mainHandler.post(() -> {
                showBubble();
                processBitmap(bitmap);
            });
        } catch (Exception exception) {
            captureNextFrame = false;
            mainHandler.post(() -> showFailure(
                    "Không đọc được ảnh màn hình: " + readableError(exception)
            ));
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) {
            throw new IllegalStateException("Ảnh không có dữ liệu");
        }

        Image.Plane plane = planes[0];
        ByteBuffer buffer = plane.getBuffer();
        buffer.rewind();

        int width = image.getWidth();
        int height = image.getHeight();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = Math.max(0, rowStride - pixelStride * width);
        int paddedWidth = width + rowPadding / pixelStride;

        Bitmap paddedBitmap = Bitmap.createBitmap(
                paddedWidth,
                height,
                Bitmap.Config.ARGB_8888
        );
        paddedBitmap.copyPixelsFromBuffer(buffer);

        if (paddedWidth == width) {
            return paddedBitmap;
        }

        Bitmap croppedBitmap = Bitmap.createBitmap(paddedBitmap, 0, 0, width, height);
        paddedBitmap.recycle();
        return croppedBitmap;
    }

    private void processBitmap(Bitmap bitmap) {
        if (cleaningUp) {
            bitmap.recycle();
            busy = false;
            updateBubbleVisual();
            return;
        }

        final int imageWidth = bitmap.getWidth();
        final int imageHeight = bitmap.getHeight();

        if (!hasSameOrientation(imageWidth, imageHeight, screenWidth, screenHeight)) {
            bitmap.recycle();
            busy = false;
            captureNextFrame = false;
            resizeProjection();
            updateBubbleVisual();
            showBubble();
            showToast("Màn hình vừa xoay. Chạm 译 để dịch lại.");
            return;
        }

        InputImage inputImage = InputImage.fromBitmap(bitmap, 0);

        textRecognizer.process(inputImage)
                .addOnSuccessListener(result -> {
                    if (cleaningUp) {
                        return;
                    }

                    List<OcrRegion> regions = extractChineseRegions(
                            result,
                            imageWidth,
                            imageHeight
                    );

                    if (regions.isEmpty()) {
                        busy = false;
                        updateBubbleVisual();
                        showToast("Không tìm thấy chữ Trung trên màn hình.");
                        return;
                    }

                    translateRegions(regions);
                })
                .addOnFailureListener(exception ->
                        showFailure("Không nhận dạng được chữ: " + readableError(exception)))
                .addOnCompleteListener(task -> {
                    if (!bitmap.isRecycled()) {
                        bitmap.recycle();
                    }
                });
    }

    private List<OcrRegion> extractChineseRegions(
            Text result,
            int imageWidth,
            int imageHeight
    ) {
        List<OcrRegion> regions = new ArrayList<>();

        for (Text.TextBlock block : result.getTextBlocks()) {
            String source = cleanRecognizedText(block.getText());
            Rect bounds = block.getBoundingBox();

            if (source.isEmpty() || bounds == null || !containsChinese(source)) {
                continue;
            }

            // Bỏ dòng điều khoản/quảng cáo cực dài nhưng rất nhỏ ở chân màn hình.
            if (source.length() > 140 && bounds.height() < imageHeight * 0.09f) {
                continue;
            }

            regions.add(new OcrRegion(
                    source,
                    new Rect(bounds),
                    imageWidth,
                    imageHeight,
                    Math.max(1, block.getLines().size())
            ));

            if (regions.size() >= MAX_REGIONS) {
                break;
            }
        }

        return regions;
    }

    private boolean containsChinese(String text) {
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if ((character >= '\u3400' && character <= '\u4DBF')
                    || (character >= '\u4E00' && character <= '\u9FFF')
                    || (character >= '\uF900' && character <= '\uFAFF')) {
                return true;
            }
        }
        return false;
    }

    private String cleanRecognizedText(String text) {
        if (text == null) {
            return "";
        }
        return text.trim()
                .replaceAll("[\\t ]+", " ")
                .replaceAll("\\n{3,}", "\n\n");
    }

    private void translateRegions(List<OcrRegion> regions) {
        clearTranslations();
        prepareSourceBounds(regions);
        performRegionTranslations(regions);
    }

    private void prepareSourceBounds(List<OcrRegion> regions) {
        sourceTranslationBounds.clear();
        for (OcrRegion region : regions) {
            Rect bounds = mapRegionToOverlay(region);
            if (bounds.width() >= 2 && bounds.height() >= 2) {
                sourceTranslationBounds.add(bounds);
            }
        }
    }

    private void performRegionTranslations(List<OcrRegion> regions) {
        if (onlineTranslationExecutor == null
                || onlineTranslationExecutor.isShutdown()) {
            showFailure("Bộ dịch online chưa sẵn sàng. Hãy bật lại bong bóng.");
            return;
        }

        showToast("Đang dịch online sát nghĩa hơn…");

        int generation = captureSequence;
        List<CompletableFuture<RegionTranslation>> futures = new ArrayList<>();

        for (OcrRegion region : regions) {
            CompletableFuture<RegionTranslation> future =
                    CompletableFuture.supplyAsync(
                            () -> translateRegionOnline(region, generation),
                            onlineTranslationExecutor
                    );
            futures.add(future);
        }

        CompletableFuture
                .allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((unused, error) -> {
                    List<RegionTranslation> onlineResults = new ArrayList<>();
                    int failedCount = 0;

                    for (CompletableFuture<RegionTranslation> future : futures) {
                        RegionTranslation result = null;
                        try {
                            result = future.getNow(null);
                        } catch (Exception ignored) {
                            // Báo vùng lỗi sau khi các vùng còn lại hoàn tất.
                        }

                        if (result != null && !TextUtils.isEmpty(result.translated)) {
                            onlineResults.add(result);
                        } else {
                            failedCount++;
                        }
                    }

                    int finalFailedCount = failedCount;
                    mainHandler.post(() -> {
                        if (cleaningUp || generation != captureSequence) {
                            return;
                        }

                        int displayed = renderTranslations(onlineResults);
                        finishOnlineTranslation(
                                generation,
                                displayed,
                                finalFailedCount
                        );
                    });
                });
    }

    private RegionTranslation translateRegionOnline(
            OcrRegion region,
            int generation
    ) {
        if (cleaningUp || generation != captureSequence) {
            return new RegionTranslation(region, null);
        }

        String source = region.source.length() > 900
                ? region.source.substring(0, 900)
                : region.source;

        String fixedTerm = exactGameTranslation(source);
        if (fixedTerm != null) {
            return new RegionTranslation(region, fixedTerm);
        }

        try {
            String translated = translateOnline(source);
            return new RegionTranslation(
                    region,
                    compactTranslation(source, translated)
            );
        } catch (Exception ignored) {
            return new RegionTranslation(region, null);
        }
    }

    private String translateOnline(String source) throws Exception {
        String cached = translationCache.get(source);
        if (!TextUtils.isEmpty(cached)) {
            return cached;
        }

        HttpURLConnection connection = null;
        try {
            byte[] body = ("q=" + URLEncoder.encode(
                    source,
                    StandardCharsets.UTF_8.name()
            )).getBytes(StandardCharsets.UTF_8);

            connection = (HttpURLConnection) new URL(
                    ONLINE_TRANSLATE_URL
            ).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5500);
            connection.setReadTimeout(8500);
            connection.setDoOutput(true);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8"
            );
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android) BongBongDich/1.4"
            );
            connection.setFixedLengthStreamingMode(body.length);

            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(body);
            }

            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("HTTP " + responseCode);
            }

            String payload;
            try (InputStream inputStream = connection.getInputStream()) {
                payload = readUtf8(inputStream);
            }

            JSONArray root = new JSONArray(payload);
            JSONArray segments = root.optJSONArray(0);
            if (segments == null) {
                throw new IOException("Phản hồi dịch không hợp lệ");
            }

            StringBuilder translated = new StringBuilder();
            for (int index = 0; index < segments.length(); index++) {
                JSONArray segment = segments.optJSONArray(index);
                if (segment == null) {
                    continue;
                }
                String part = segment.optString(0, "");
                if (!part.isEmpty()) {
                    translated.append(part);
                }
            }

            String result = translated.toString().trim();
            if (result.isEmpty()) {
                throw new IOException("Bản dịch trống");
            }

            if (translationCache.size() > 500) {
                translationCache.clear();
            }
            translationCache.put(source, result);
            return result;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String readUtf8(InputStream inputStream) throws IOException {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8)
        )) {
            char[] buffer = new char[2048];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                result.append(buffer, 0, read);
            }
        }
        return result.toString();
    }

    private int renderTranslations(List<RegionTranslation> translations) {
        translations.sort((first, second) -> {
            int topComparison = Integer.compare(
                    first.region.bounds.top,
                    second.region.bounds.top
            );
            if (topComparison != 0) {
                return topComparison;
            }
            return Integer.compare(
                    first.region.bounds.left,
                    second.region.bounds.left
            );
        });

        int displayed = 0;
        for (RegionTranslation translation : translations) {
            if (addTranslationAtPosition(
                    translation.region,
                    translation.translated
            )) {
                displayed++;
            }
        }
        return displayed;
    }

    private void finishOnlineTranslation(
            int generation,
            int displayedCount,
            int failedCount
    ) {
        if (cleaningUp || generation != captureSequence) {
            return;
        }

        busy = false;
        updateBubbleVisual();

        if (displayedCount == 0) {
            showToast("Dịch online thất bại. Kiểm tra mạng rồi chạm 译 lại.");
        } else if (failedCount > 0) {
            showToast("Đã dịch; " + failedCount + " ô bị lỗi, chạm lại để thử.");
        } else {
            showToast("Đã dịch online. Chạm × để ẩn.");
        }
    }

    private String exactGameTranslation(String source) {
        String key = source.replaceAll("[\\[\\]【】（）()\\s:：]", "");

        switch (key) {
            case "活动":
                return "Sự kiện";
            case "公告":
                return "Thông báo";
            case "领取":
                return "Nhận";
            case "已领取":
                return "Đã nhận";
            case "充值":
                return "Nạp";
            case "首充":
                return "Nạp đầu";
            case "购买":
                return "Mua";
            case "返回":
                return "Quay lại";
            case "确定":
            case "确认":
                return "Xác nhận";
            case "取消":
                return "Hủy";
            case "开启":
                return "Mở";
            case "继续":
                return "Tiếp tục";
            case "跳过":
                return "Bỏ qua";
            case "前往":
                return "Đi tới";
            case "免费":
                return "Miễn phí";
            case "角色":
                return "Nhân vật";
            case "背包":
                return "Túi đồ";
            case "装备":
                return "Trang bị";
            case "任务":
                return "Nhiệm vụ";
            case "主线":
                return "Cốt truyện";
            case "支线":
                return "Nhiệm vụ phụ";
            case "商城":
            case "商店":
                return "Cửa hàng";
            case "挑战":
                return "Khiêu chiến";
            case "竞技场":
                return "Đấu trường";
            case "副本":
                return "Phó bản";
            case "关卡":
                return "Ải";
            case "战力":
            case "战斗力":
                return "Lực chiến";
            case "等级":
                return "Cấp";
            case "升级":
                return "Nâng cấp";
            case "进阶":
                return "Tiến bậc";
            case "突破":
                return "Đột phá";
            case "升星":
                return "Tăng sao";
            case "阵容":
                return "Đội hình";
            case "招募":
                return "Chiêu mộ";
            case "召唤":
                return "Triệu hồi";
            case "技能":
                return "Kỹ năng";
            case "天赋":
                return "Thiên phú";
            case "奖励":
                return "Phần thưởng";
            case "邮件":
                return "Thư";
            case "好友":
                return "Bạn bè";
            case "公会":
            case "帮会":
                return "Bang hội";
            case "排行":
            case "排行榜":
                return "Xếp hạng";
            case "设置":
                return "Cài đặt";
            case "兑换":
                return "Đổi";
            case "签到":
                return "Điểm danh";
            case "限时":
                return "Giới hạn";
            case "已完成":
                return "Đã hoàn thành";
            case "未解锁":
                return "Chưa mở";
            case "扫荡":
                return "Quét";
            case "挂机":
                return "Treo máy";
            case "体力":
                return "Thể lực";
            case "元宝":
                return "Nguyên bảo";
            case "金币":
                return "Vàng";
            case "战斗":
                return "Chiến đấu";
            case "十连抽":
                return "Quay 10 lần";
            case "单抽":
                return "Quay 1 lần";
            case "攻击":
                return "Công";
            case "防御":
                return "Thủ";
            case "生命":
                return "Sinh lực";
            case "暴击":
                return "Bạo kích";
            case "命中":
                return "Chính xác";
            case "闪避":
                return "Né tránh";
            default:
                return null;
        }
    }

    private String compactTranslation(String source, String translated) {
        String exact = exactGameTranslation(source);
        if (exact != null) {
            return exact;
        }

        String result = translated.trim()
                .replaceAll("^\\s*\\[", "")
                .replaceAll("\\]\\s*$", "");

        if (source.contains("装备")) {
            result = result.replaceAll(
                    "(?iu)(trang thiết bị|thiết bị)",
                    "trang bị"
            );
        }
        if (source.contains("副本")) {
            result = result.replaceAll(
                    "(?iu)(bản sao|bản copy|phụ bản)",
                    "phó bản"
            );
        }
        if (source.contains("战力") || source.contains("战斗力")) {
            result = result.replaceAll(
                    "(?iu)(sức mạnh chiến đấu|khả năng chiến đấu|chiến lực)",
                    "lực chiến"
            );
        }
        if (source.contains("关卡")) {
            result = result.replaceAll(
                    "(?iu)(cấp độ|màn chơi)",
                    "ải"
            );
        }
        if (source.contains("阵容")) {
            result = result.replaceAll(
                    "(?iu)(đội ngũ|đội hình chiến đấu)",
                    "đội hình"
            );
        }
        if (source.contains("招募")) {
            result = result.replaceAll(
                    "(?iu)(tuyển dụng|tuyển người)",
                    "chiêu mộ"
            );
        }
        if (source.contains("碎片")) {
            result = result.replaceAll(
                    "(?iu)(mảnh vỡ|mảnh vụn)",
                    "mảnh"
            );
        }
        if (source.contains("羁绊")) {
            result = result.replaceAll(
                    "(?iu)(mối ràng buộc|sự ràng buộc|liên kết)",
                    "duyên"
            );
        }
        if (source.contains("抽")) {
            result = result.replaceAll(
                    "(?iu)(rút thăm|rút|vẽ)",
                    "quay"
            );
        }

        return result;
    }

    private void createTranslationLayer() {
        if (translationLayer != null) {
            return;
        }

        translationLayer = new FrameLayout(this);
        translationLayer.setBackgroundColor(Color.TRANSPARENT);
        translationLayer.setVisibility(View.INVISIBLE);
        translationLayer.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        );

        translationLayerParams = new WindowManager.LayoutParams(
                Math.max(1, screenWidth),
                Math.max(1, screenHeight),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        translationLayerParams.gravity = Gravity.TOP | Gravity.START;
        translationLayerParams.alpha = 0.79f;

        windowManager.addView(translationLayer, translationLayerParams);
    }

    private boolean addTranslationAtPosition(OcrRegion region, String translated) {
        if (translationLayer == null || cleaningUp || TextUtils.isEmpty(translated)) {
            return false;
        }

        Rect mappedBounds = mapRegionToOverlay(region);
        if (mappedBounds.width() < 2 || mappedBounds.height() < 2) {
            return false;
        }

        Rect displayBounds = chooseReadableBounds(
                mappedBounds,
                region.source,
                translated
        );

        if (overlapsExistingRegion(displayBounds)) {
            displayBounds = new Rect(mappedBounds);
        }
        if (overlapsExistingRegion(displayBounds)) {
            return false;
        }

        TextView label = new TextView(this);
        label.setText(translated);
        label.setTextColor(Color.WHITE);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setGravity(region.lineCount > 1
                ? Gravity.START | Gravity.CENTER_VERTICAL
                : Gravity.CENTER);
        label.setPadding(dp(2), 0, dp(2), 0);
        label.setLineSpacing(0, 0.96f);
        label.setIncludeFontPadding(false);
        label.setMaxLines(Math.max(
                1,
                region.lineCount + (displayBounds.height() > mappedBounds.height() ? 2 : 1)
        ));
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setAutoSizeTextTypeUniformWithConfiguration(
                6,
                18,
                1,
                TypedValue.COMPLEX_UNIT_SP
        );

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.parseColor("#EC1A1722"));
        background.setCornerRadius(dp(4));
        background.setStroke(dp(1), Color.parseColor("#8F7BFF"));
        label.setBackground(background);

        FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(
                displayBounds.width(),
                displayBounds.height()
        );
        labelParams.leftMargin = displayBounds.left;
        labelParams.topMargin = displayBounds.top;

        translationLayer.addView(label, labelParams);
        placedTranslationBounds.add(new Rect(displayBounds));
        translationsVisible = true;
        translationLayer.setVisibility(View.VISIBLE);
        updateBubbleVisual();
        return true;
    }

    private Rect chooseReadableBounds(
            Rect original,
            String source,
            String translated
    ) {
        int sourceLength = Math.max(
                1,
                source.replaceAll("\\s", "").length()
        );
        int translatedLength = translated.replaceAll("\\s", "").length();

        if (translatedLength <= sourceLength * 1.25f) {
            return new Rect(original);
        }

        int growX = Math.min(dp(28), Math.max(0, original.width() / 5));
        int growY = Math.min(dp(10), Math.max(0, original.height() / 5));

        int overlayWidth = translationLayer.getWidth() > 0
                ? translationLayer.getWidth()
                : screenWidth;
        int overlayHeight = translationLayer.getHeight() > 0
                ? translationLayer.getHeight()
                : screenHeight;

        Rect candidate = new Rect(
                original.left - growX / 2,
                original.top - growY / 2,
                original.right + growX - growX / 2,
                original.bottom + growY - growY / 2
        );

        int candidateWidth = Math.min(candidate.width(), overlayWidth);
        int candidateHeight = Math.min(candidate.height(), overlayHeight);
        candidate.left = clamp(
                candidate.left,
                0,
                Math.max(0, overlayWidth - candidateWidth)
        );
        candidate.top = clamp(
                candidate.top,
                0,
                Math.max(0, overlayHeight - candidateHeight)
        );
        candidate.right = candidate.left + candidateWidth;
        candidate.bottom = candidate.top + candidateHeight;

        if (coversAnotherSource(candidate, original)
                || overlapsExistingRegion(candidate)) {
            return new Rect(original);
        }

        return candidate;
    }

    private boolean coversAnotherSource(Rect candidate, Rect ownSource) {
        for (Rect sourceBounds : sourceTranslationBounds) {
            if (sourceBounds.equals(ownSource)) {
                continue;
            }
            if (Rect.intersects(candidate, sourceBounds)) {
                return true;
            }
        }
        return false;
    }

    private Rect mapRegionToOverlay(OcrRegion region) {
        int overlayWidth = translationLayer.getWidth() > 0
                ? translationLayer.getWidth()
                : screenWidth;
        int overlayHeight = translationLayer.getHeight() > 0
                ? translationLayer.getHeight()
                : screenHeight;

        if (!hasSameOrientation(
                region.imageWidth,
                region.imageHeight,
                overlayWidth,
                overlayHeight
        )) {
            return new Rect();
        }

        float scaleX = overlayWidth / (float) Math.max(1, region.imageWidth);
        float scaleY = overlayHeight / (float) Math.max(1, region.imageHeight);

        int[] layerLocation = new int[]{0, 0};
        translationLayer.getLocationOnScreen(layerLocation);

        int left = Math.round(region.bounds.left * scaleX) - layerLocation[0];
        int top = Math.round(region.bounds.top * scaleY) - layerLocation[1];
        int right = Math.round(region.bounds.right * scaleX) - layerLocation[0];
        int bottom = Math.round(region.bounds.bottom * scaleY) - layerLocation[1];

        left = clamp(left, 0, Math.max(0, overlayWidth - 1));
        top = clamp(top, 0, Math.max(0, overlayHeight - 1));
        right = clamp(right, left + 1, Math.max(left + 1, overlayWidth));
        bottom = clamp(bottom, top + 1, Math.max(top + 1, overlayHeight));

        return new Rect(left, top, right, bottom);
    }

    private boolean overlapsExistingRegion(Rect candidate) {
        long candidateArea = Math.max(1L, (long) candidate.width() * candidate.height());

        for (Rect existing : placedTranslationBounds) {
            Rect intersection = new Rect();
            if (!intersection.setIntersect(candidate, existing)) {
                continue;
            }

            long existingArea = Math.max(1L, (long) existing.width() * existing.height());
            long intersectionArea =
                    (long) intersection.width() * intersection.height();
            float overlapRatio = intersectionArea
                    / (float) Math.min(candidateArea, existingArea);

            if (overlapRatio > 0.55f) {
                return true;
            }
        }
        return false;
    }

    private void clearTranslations() {
        placedTranslationBounds.clear();
        sourceTranslationBounds.clear();
        translationsVisible = false;

        if (translationLayer != null) {
            translationLayer.removeAllViews();
            translationLayer.setVisibility(View.INVISIBLE);
        }

        updateBubbleVisual();
    }

    private void handleBubbleTap() {
        if (translationsVisible || busy) {
            boolean wasVisible = translationsVisible;
            captureSequence++;
            captureNextFrame = false;
            busy = false;
            clearTranslations();
            showBubble();
            showToast(wasVisible ? "Đã tắt bản dịch." : "Đã hủy dịch.");
            return;
        }

        requestCapture();
    }

    private void requestCapture() {
        resizeProjection();

        if (imageReader == null || virtualDisplay == null) {
            showFailure("Chưa sẵn sàng chụp màn hình. Hãy bật lại bong bóng.");
            return;
        }

        clearTranslations();
        busy = true;
        captureNextFrame = false;
        int sequence = ++captureSequence;
        updateBubbleVisual();

        hideBubble();
        showToast("Đang cập nhật ảnh màn hình…");

        mainHandler.postDelayed(() -> {
            if (cleaningUp || sequence != captureSequence) {
                return;
            }
            captureNextFrame = true;

            mainHandler.postDelayed(() -> {
                if (captureNextFrame && sequence == captureSequence) {
                    captureNextFrame = false;
                    showFailure("Không chụp được màn hình. Hãy thử lại.");
                }
            }, 2000);
        }, 300);
    }

    private void createBubble() {
        if (bubbleView != null) {
            return;
        }

        bubbleView = new TextView(this);
        bubbleView.setTextColor(Color.WHITE);
        bubbleView.setTextSize(25);
        bubbleView.setTypeface(Typeface.DEFAULT_BOLD);
        bubbleView.setGravity(Gravity.CENTER);
        bubbleView.setIncludeFontPadding(false);

        bubbleBackground = new GradientDrawable();
        bubbleBackground.setShape(GradientDrawable.OVAL);
        bubbleBackground.setStroke(dp(2), Color.parseColor("#66FFFFFF"));
        bubbleView.setBackground(bubbleBackground);
        bubbleView.setElevation(dp(9));

        bubbleParams = new WindowManager.LayoutParams(
                dp(62),
                dp(62),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = Math.max(dp(8), screenWidth - dp(78));
        bubbleParams.y = dp(180);

        attachBubbleTouchHandler();
        updateBubbleVisual();
        windowManager.addView(bubbleView, bubbleParams);
    }

    private void updateBubbleVisual() {
        if (bubbleView == null || bubbleBackground == null) {
            return;
        }

        if (translationsVisible) {
            bubbleView.setText("×");
            bubbleView.setContentDescription("Chạm để tắt bản dịch");
            bubbleBackground.setColor(Color.parseColor("#D64545"));
        } else if (busy) {
            bubbleView.setText("…");
            bubbleView.setContentDescription("Chạm để hủy dịch");
            bubbleBackground.setColor(Color.parseColor("#D88A20"));
        } else {
            bubbleView.setText("译");
            bubbleView.setContentDescription("Chạm để dịch, kéo để di chuyển");
            bubbleBackground.setColor(Color.parseColor("#6847F5"));
        }

        bubbleView.invalidate();
    }

    private void attachBubbleTouchHandler() {
        int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        bubbleView.setOnTouchListener(new View.OnTouchListener() {
            private int startX;
            private int startY;
            private float touchStartX;
            private float touchStartY;
            private boolean moved;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = bubbleParams.x;
                        startY = bubbleParams.y;
                        touchStartX = event.getRawX();
                        touchStartY = event.getRawY();
                        moved = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        int deltaX = Math.round(event.getRawX() - touchStartX);
                        int deltaY = Math.round(event.getRawY() - touchStartY);
                        if (Math.abs(deltaX) > touchSlop || Math.abs(deltaY) > touchSlop) {
                            moved = true;
                        }

                        bubbleParams.x = clamp(
                                startX + deltaX,
                                0,
                                Math.max(0, screenWidth - bubbleParams.width)
                        );
                        bubbleParams.y = clamp(
                                startY + deltaY,
                                0,
                                Math.max(0, screenHeight - bubbleParams.height)
                        );

                        try {
                            windowManager.updateViewLayout(bubbleView, bubbleParams);
                        } catch (Exception ignored) {
                            // Dịch vụ có thể đang dừng.
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            handleBubbleTap();
                        }
                        return true;

                    case MotionEvent.ACTION_CANCEL:
                        return true;

                    default:
                        return false;
                }
            }
        });
    }

    private void hideBubble() {
        if (bubbleView != null) {
            bubbleView.setVisibility(View.INVISIBLE);
        }
    }

    private void showBubble() {
        if (bubbleView != null) {
            updateBubbleVisual();
            bubbleView.setVisibility(View.VISIBLE);
        }
    }

    private void showFailure(String message) {
        busy = false;
        captureNextFrame = false;
        clearTranslations();
        showBubble();
        showToast(message);
    }

    private int[] readCurrentDisplaySize() {
        int width = 0;
        int height = 0;

        try {
            DisplayMetrics realMetrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(realMetrics);
            width = realMetrics.widthPixels;
            height = realMetrics.heightPixels;
        } catch (Exception ignored) {
            // Dùng WindowMetrics ở dưới nếu máy không trả được real metrics.
        }

        if ((width <= 0 || height <= 0) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowMetrics metrics = windowManager.getMaximumWindowMetrics();
            Rect bounds = metrics.getBounds();
            width = bounds.width();
            height = bounds.height();
        }

        if (width <= 0 || height <= 0) {
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            width = metrics.widthPixels;
            height = metrics.heightPixels;
        }

        int orientation = getResources().getConfiguration().orientation;
        if (orientation == Configuration.ORIENTATION_LANDSCAPE && width < height) {
            int temporary = width;
            width = height;
            height = temporary;
        } else if (orientation == Configuration.ORIENTATION_PORTRAIT && width > height) {
            int temporary = width;
            width = height;
            height = temporary;
        }

        return new int[]{Math.max(1, width), Math.max(1, height)};
    }

    private void updateScreenBounds() {
        densityDpi = getResources().getConfiguration().densityDpi;
        int[] size = readCurrentDisplaySize();
        screenWidth = size[0];
        screenHeight = size[1];
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mainHandler.postDelayed(this::resizeProjection, 350);
    }

    private void resizeProjection() {
        if (cleaningUp || virtualDisplay == null) {
            return;
        }

        int[] size = readCurrentDisplaySize();
        resizeProjectionTo(size[0], size[1]);
    }

    private void resizeProjectionTo(int targetWidth, int targetHeight) {
        if (cleaningUp || virtualDisplay == null
                || targetWidth <= 0 || targetHeight <= 0) {
            return;
        }

        targetWidth = Math.max(1, targetWidth);
        targetHeight = Math.max(1, targetHeight);
        densityDpi = getResources().getConfiguration().densityDpi;

        boolean readerAlreadyMatches = imageReader != null
                && imageReader.getWidth() == targetWidth
                && imageReader.getHeight() == targetHeight;

        if (readerAlreadyMatches) {
            screenWidth = targetWidth;
            screenHeight = targetHeight;
            ensureOverlayGeometry();
            clampBubbleToScreen();
            return;
        }

        int oldWidth = screenWidth;
        int oldHeight = screenHeight;
        ImageReader oldReader = imageReader;
        ImageReader newReader = createImageReader(targetWidth, targetHeight);

        captureSequence++;
        busy = false;
        captureNextFrame = false;
        clearTranslations();

        try {
            virtualDisplay.resize(targetWidth, targetHeight, densityDpi);
            virtualDisplay.setSurface(newReader.getSurface());

            imageReader = newReader;
            screenWidth = targetWidth;
            screenHeight = targetHeight;

            recreateTranslationLayer();
            clampBubbleToScreen();

            if (oldReader != null) {
                oldReader.setOnImageAvailableListener(null, null);
                oldReader.close();
            }
        } catch (Exception exception) {
            newReader.setOnImageAvailableListener(null, null);
            newReader.close();
            imageReader = oldReader;
            screenWidth = oldWidth;
            screenHeight = oldHeight;

            try {
                if (oldReader != null) {
                    virtualDisplay.resize(oldWidth, oldHeight, densityDpi);
                    virtualDisplay.setSurface(oldReader.getSurface());
                }
            } catch (Exception ignored) {
                // Giữ phiên hiện tại nếu máy không cho khôi phục kích thước cũ.
            }

            showToast("Chưa đồng bộ được màn hình, hãy chạm 译 lại.");
        }
    }

    private void ensureOverlayGeometry() {
        if (translationLayer == null || translationLayerParams == null) {
            return;
        }

        translationLayerParams.width = Math.max(1, screenWidth);
        translationLayerParams.height = Math.max(1, screenHeight);
        translationLayerParams.x = 0;
        translationLayerParams.y = 0;

        try {
            windowManager.updateViewLayout(translationLayer, translationLayerParams);
        } catch (Exception ignored) {
            // Lớp phủ có thể đang được tái tạo.
        }
    }

    private void recreateTranslationLayer() {
        if (translationLayer != null) {
            try {
                windowManager.removeViewImmediate(translationLayer);
            } catch (Exception ignored) {
                // Lớp phủ đã được gỡ.
            }
            translationLayer = null;
            translationLayerParams = null;
        }
        createTranslationLayer();
    }

    private void clampBubbleToScreen() {
        if (bubbleView == null || bubbleParams == null) {
            return;
        }

        bubbleParams.x = clamp(
                bubbleParams.x,
                0,
                Math.max(0, screenWidth - bubbleParams.width)
        );
        bubbleParams.y = clamp(
                bubbleParams.y,
                0,
                Math.max(0, screenHeight - bubbleParams.height)
        );

        try {
            windowManager.updateViewLayout(bubbleView, bubbleParams);
        } catch (Exception ignored) {
            // Dịch vụ có thể đang dừng.
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Bong bóng dịch",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Giữ bong bóng dịch hoạt động trên màn hình");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void startAsForeground() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                1,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, BubbleService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                2,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Bong Bóng Dịch")
                .setContentText("译: dịch màn hình • ×: tắt bản dịch")
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "Tắt ứng dụng",
                        stopPendingIntent
                )
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void showToast(String message) {
        mainHandler.post(() ->
                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show());
    }

    private boolean hasSameOrientation(
            int firstWidth,
            int firstHeight,
            int secondWidth,
            int secondHeight
    ) {
        return (firstWidth >= firstHeight) == (secondWidth >= secondHeight);
    }

    private String readableError(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        cleaningUp = true;
        busy = false;
        captureNextFrame = false;
        captureSequence++;

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_RUNNING, false)
                .apply();

        if (translationLayer != null) {
            try {
                windowManager.removeView(translationLayer);
            } catch (Exception ignored) {
                // View đã được gỡ.
            }
            translationLayer = null;
        }

        if (bubbleView != null) {
            try {
                windowManager.removeView(bubbleView);
            } catch (Exception ignored) {
                // View đã được gỡ.
            }
            bubbleView = null;
        }

        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
            imageReader.close();
            imageReader = null;
        }

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (mediaProjection != null) {
            try {
                if (mediaProjectionCallback != null) {
                    mediaProjection.unregisterCallback(mediaProjectionCallback);
                }
                mediaProjection.stop();
            } catch (Exception ignored) {
                // Phiên chia sẻ đã dừng.
            }
            mediaProjection = null;
        }

        if (textRecognizer != null) {
            textRecognizer.close();
        }

        if (captureThread != null) {
            captureThread.quitSafely();
        }
        if (onlineTranslationExecutor != null) {
            onlineTranslationExecutor.shutdownNow();
        }
        translationCache.clear();

        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private static final class RegionTranslation {
        final OcrRegion region;
        final String translated;

        RegionTranslation(OcrRegion region, String translated) {
            this.region = region;
            this.translated = translated;
        }
    }

    private static final class OcrRegion {
        final String source;
        final Rect bounds;
        final int imageWidth;
        final int imageHeight;
        final int lineCount;

        OcrRegion(
                String source,
                Rect bounds,
                int imageWidth,
                int imageHeight,
                int lineCount
        ) {
            this.source = source;
            this.bounds = bounds;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
            this.lineCount = lineCount;
        }
    }
}
