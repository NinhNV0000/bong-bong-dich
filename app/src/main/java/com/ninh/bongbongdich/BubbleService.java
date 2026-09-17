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
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final int MAX_OCR_LONG_EDGE = 2200;
    private static final int MAX_BATCH_CHARACTERS = 2600;
    private static final Pattern NUMBER_TOKEN_PATTERN = Pattern.compile(
            "\\d+(?:[.,]\\d+)*%?"
    );
    private static final String[] PROTECTED_GAME_TERMS = new String[]{
            "漩涡鸣人", "宇智波佐助", "春野樱", "旗木卡卡西",
            "宇智波鼬", "宇智波带土", "宇智波斑", "日向雏田",
            "鸣人仙人", "仙人鸣人", "九尾鸣人", "六道鸣人",
            "轮回眼佐助", "排行榜", "战斗力", "竞技场",
            "已领取", "未解锁", "已完成", "十连抽",
            "通灵兽", "查克拉", "活动", "公告", "领取",
            "充值", "首充", "购买", "返回", "确定", "确认",
            "取消", "开启", "继续", "跳过", "前往", "免费",
            "角色", "背包", "装备", "任务", "主线", "支线",
            "商城", "商店", "挑战", "副本", "关卡", "战力",
            "等级", "升级", "进阶", "突破", "升星", "阵容",
            "招募", "召唤", "技能", "天赋", "奖励", "邮件",
            "好友", "公会", "帮会", "排行", "设置", "兑换",
            "签到", "限时", "扫荡", "挂机", "体力", "元宝",
            "金币", "战斗", "单抽", "攻击", "防御", "生命",
            "暴击", "命中", "闪避", "碎片", "羁绊", "阵营",
            "鸣人", "佐助", "小樱", "卡卡西", "纲手", "自来也",
            "大蛇丸", "我爱罗", "雏田", "带土", "六道", "火影",
            "忍者", "忍术", "尾兽", "忍界"
    };
    private static final Pattern BATCH_MARKER_PATTERN = Pattern.compile(
            "\\[\\s*\\[\\s*\\[\\s*BBD\\s*[_-]?\\s*(\\d+)"
                    + "\\s*\\]\\s*\\]\\s*\\]",
            Pattern.CASE_INSENSITIVE
    );
    private static final String ONLINE_TRANSLATE_URL =
            "https://translate.googleapis.com/translate_a/single"
                    + "?client=gtx&sl=zh-CN&tl=vi&dt=t";

    private WindowManager windowManager;
    private TextView bubbleView;
    private GradientDrawable bubbleBackground;
    private WindowManager.LayoutParams bubbleParams;

    private TextView dismissTargetView;
    private GradientDrawable dismissTargetBackground;
    private WindowManager.LayoutParams dismissTargetParams;
    private boolean bubbleOverDismissTarget;

    private FrameLayout translationLayer;
    private WindowManager.LayoutParams translationLayerParams;
    private ImageView frozenScreenView;
    private TextView frozenScreenBar;
    private Bitmap frozenScreenBitmap;
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
    private volatile Map<String, String> customGlossary = new LinkedHashMap<>();

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
        onlineTranslationExecutor = Executors.newFixedThreadPool(8);
        refreshCustomGlossary();

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
        createDismissTarget();
        createBubble();

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_RUNNING, true)
                .apply();

        showToast("Chạm 译 để dịch; kéo bong bóng xuống × để tắt.");
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

        final int capturedWidth = bitmap.getWidth();
        final int capturedHeight = bitmap.getHeight();

        if (!hasSameOrientation(capturedWidth, capturedHeight, screenWidth, screenHeight)) {
            bitmap.recycle();
            busy = false;
            captureNextFrame = false;
            resizeProjection();
            updateBubbleVisual();
            showBubble();
            showToast("Màn hình vừa xoay. Chạm 译 để dịch lại.");
            return;
        }

        Bitmap ocrBitmap = scaleBitmapForOcr(bitmap);
        final int imageWidth = ocrBitmap.getWidth();
        final int imageHeight = ocrBitmap.getHeight();
        InputImage inputImage = InputImage.fromBitmap(ocrBitmap, 0);

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

                    translateRegions(regions, bitmap);
                })
                .addOnFailureListener(exception ->
                        showFailure("Không nhận dạng được chữ: " + readableError(exception)))
                .addOnCompleteListener(task -> {
                    if (ocrBitmap != bitmap && !ocrBitmap.isRecycled()) {
                        ocrBitmap.recycle();
                    }
                    if (bitmap != frozenScreenBitmap && !bitmap.isRecycled()) {
                        bitmap.recycle();
                    }
                });
    }

    private Bitmap scaleBitmapForOcr(Bitmap source) {
        int longEdge = Math.max(source.getWidth(), source.getHeight());
        if (longEdge <= MAX_OCR_LONG_EDGE) {
            return source;
        }

        float scale = MAX_OCR_LONG_EDGE / (float) longEdge;
        int width = Math.max(1, Math.round(source.getWidth() * scale));
        int height = Math.max(1, Math.round(source.getHeight() * scale));
        return Bitmap.createScaledBitmap(source, width, height, true);
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

        String normalized = text.trim()
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t ]+", " ")
                .replaceAll("(?<=\\p{IsHan})[\\t ]+(?=\\p{IsHan})", "")
                .replaceAll("[\\t ]*\\n[\\t ]*", "\n")
                .replaceAll("\\n{3,}", "\n\n");

        return joinSoftOcrLineBreaks(normalized);
    }

    private String joinSoftOcrLineBreaks(String text) {
        String[] paragraphs = text.split("\\n\\s*\\n", -1);
        StringBuilder joinedText = new StringBuilder();

        for (String paragraph : paragraphs) {
            String[] lines = paragraph.split("\\n");
            StringBuilder joinedParagraph = new StringBuilder();

            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isEmpty()) {
                    continue;
                }

                if (joinedParagraph.length() > 0) {
                    char previous = joinedParagraph.charAt(
                            joinedParagraph.length() - 1
                    );
                    char next = line.charAt(0);

                    // Chỉ thêm khoảng trắng khi một từ Latin/số bị ngắt dòng.
                    // Chữ Trung xuống dòng là ngắt hiển thị, không phải câu mới.
                    if (isAsciiWordCharacter(previous)
                            && isAsciiWordCharacter(next)) {
                        joinedParagraph.append(' ');
                    }
                }
                joinedParagraph.append(line);
            }

            if (joinedParagraph.length() == 0) {
                continue;
            }
            if (joinedText.length() > 0) {
                joinedText.append('\n');
            }
            joinedText.append(joinedParagraph);
        }

        return joinedText.toString().trim();
    }

    private boolean isAsciiWordCharacter(char character) {
        return character <= 127 && Character.isLetterOrDigit(character);
    }

    private void translateRegions(List<OcrRegion> regions, Bitmap capturedScreen) {
        refreshCustomGlossary();
        clearTranslations();
        showFrozenScreen(capturedScreen);
        prepareSourceBounds(regions);
        performRegionTranslations(regions);
    }

    private void refreshCustomGlossary() {
        Map<String, String> latest = GlossaryStore.load(this);
        if (!latest.equals(customGlossary)) {
            customGlossary = latest;
            translationCache.clear();
        }
    }

    private String exactCustomTranslation(String source) {
        if (TextUtils.isEmpty(source) || customGlossary.isEmpty()) {
            return null;
        }

        String direct = customGlossary.get(source.trim());
        if (!TextUtils.isEmpty(direct)) {
            return direct;
        }

        String normalizedSource = normalizeGlossaryKey(source);
        for (Map.Entry<String, String> entry : customGlossary.entrySet()) {
            if (normalizedSource.equals(normalizeGlossaryKey(entry.getKey()))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String applyCustomGlossaryToSource(String source) {
        if (TextUtils.isEmpty(source) || customGlossary.isEmpty()) {
            return source;
        }

        String prepared = source;
        for (Map.Entry<String, String> entry : customGlossary.entrySet()) {
            String chinese = entry.getKey();
            if (!TextUtils.isEmpty(chinese) && prepared.contains(chinese)) {
                prepared = prepared.replace(
                        chinese,
                        "「" + entry.getValue() + "」"
                );
            }
        }
        return prepared;
    }

    private boolean sourceUsesCustomGlossary(String source) {
        if (TextUtils.isEmpty(source) || customGlossary.isEmpty()) {
            return false;
        }
        for (String chinese : customGlossary.keySet()) {
            if (!TextUtils.isEmpty(chinese) && source.contains(chinese)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeGlossaryKey(String value) {
        return value == null
                ? ""
                : value.replaceAll("[\\[\\]【】（）()\\s:：，,。.!！?？]", "");
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

        showToast("Đang dịch; ô xong sẽ hiện ngay…");

        int generation = captureSequence;
        Map<String, List<OcrRegion>> groupedRegions = new LinkedHashMap<>();
        for (OcrRegion region : regions) {
            groupedRegions
                    .computeIfAbsent(region.source, ignored -> new ArrayList<>())
                    .add(region);
        }

        int requestCount = groupedRegions.size();
        int[] completedRequests = new int[]{0};
        int[] displayedRegions = new int[]{0};
        int[] failedRegions = new int[]{0};

        for (List<OcrRegion> sameSourceRegions : groupedRegions.values()) {
            OcrRegion representative = sameSourceRegions.get(0);

            CompletableFuture
                    .supplyAsync(
                            () -> translateRegionOnline(
                                    representative,
                                    generation
                            ),
                            onlineTranslationExecutor
                    )
                    .whenComplete((result, error) -> mainHandler.post(() -> {
                        if (cleaningUp || generation != captureSequence) {
                            return;
                        }

                        completedRequests[0]++;
                        String translated = result == null
                                ? null
                                : result.translated;

                        for (OcrRegion region : sameSourceRegions) {
                            if (!TextUtils.isEmpty(translated)
                                    && addTranslationAtPosition(
                                            region,
                                            translated
                                    )) {
                                displayedRegions[0]++;
                            } else {
                                failedRegions[0]++;
                            }
                        }

                        if (completedRequests[0] < requestCount) {
                            updateFrozenScreenProgress(
                                    completedRequests[0],
                                    requestCount,
                                    displayedRegions[0]
                            );
                            raiseTranslationControls();
                            showBubble();
                            return;
                        }

                        finishOnlineTranslation(
                                generation,
                                displayedRegions[0],
                                failedRegions[0]
                        );
                    }));
        }
    }

    private List<List<OcrRegion>> buildRegionBatches(List<OcrRegion> regions) {
        List<List<OcrRegion>> batches = new ArrayList<>();
        List<OcrRegion> currentBatch = new ArrayList<>();
        int currentCharacters = 0;

        for (OcrRegion region : regions) {
            int sourceLength = Math.min(region.source.length(), 900);
            int estimatedCharacters = sourceLength + 24;

            if (!currentBatch.isEmpty()
                    && currentCharacters + estimatedCharacters > MAX_BATCH_CHARACTERS) {
                batches.add(currentBatch);
                currentBatch = new ArrayList<>();
                currentCharacters = 0;
            }

            currentBatch.add(region);
            currentCharacters += estimatedCharacters;
        }

        if (!currentBatch.isEmpty()) {
            batches.add(currentBatch);
        }
        return batches;
    }

    private List<RegionTranslation> translateBatchOnline(
            List<OcrRegion> regions,
            int generation
    ) {
        List<RegionTranslation> results = new ArrayList<>();
        if (cleaningUp || generation != captureSequence) {
            return results;
        }

        List<OcrRegion> pendingRegions = new ArrayList<>();
        List<String> pendingSources = new ArrayList<>();
        StringBuilder batchRequest = new StringBuilder();

        for (OcrRegion region : regions) {
            String source = region.source.length() > 900
                    ? region.source.substring(0, 900)
                    : region.source;

            String customTerm = exactCustomTranslation(source);
            if (!TextUtils.isEmpty(customTerm)) {
                results.add(new RegionTranslation(region, customTerm));
                continue;
            }

            String fixedTerm = exactGameTranslation(source);
            if (!TextUtils.isEmpty(fixedTerm)) {
                results.add(new RegionTranslation(region, fixedTerm));
                continue;
            }

            String cached = translationCache.get(source);
            if (!TextUtils.isEmpty(cached)) {
                results.add(new RegionTranslation(
                        region,
                        compactTranslation(source, cached)
                ));
                continue;
            }

            int markerIndex = pendingRegions.size();
            pendingRegions.add(region);
            pendingSources.add(source);
            batchRequest
                    .append(batchMarker(markerIndex))
                    .append('\n')
                    .append(applyCustomGlossaryToSource(source))
                    .append('\n');
        }

        if (pendingRegions.isEmpty()) {
            return results;
        }

        Map<Integer, String> translatedParts = new HashMap<>();
        try {
            translatedParts = parseBatchTranslation(
                    translateOnline(batchRequest.toString())
            );
        } catch (Exception ignored) {
            // Nếu dịch vụ đổi định dạng, thử riêng từng ô ở dưới.
        }

        for (int index = 0; index < pendingRegions.size(); index++) {
            if (cleaningUp || generation != captureSequence) {
                return new ArrayList<>();
            }

            OcrRegion region = pendingRegions.get(index);
            String source = pendingSources.get(index);
            String translated = cleanBatchPart(translatedParts.get(index));

            if (!TextUtils.isEmpty(translated)) {
                translated = compactTranslation(source, translated);
                cacheTranslation(source, translated);
                results.add(new RegionTranslation(region, translated));
                continue;
            }

            RegionTranslation fallback = translateRegionOnline(region, generation);
            if (fallback != null && !TextUtils.isEmpty(fallback.translated)) {
                results.add(fallback);
            }
        }

        return results;
    }

    private String batchMarker(int index) {
        return "[[[BBD_" + index + "]]]";
    }

    private Map<Integer, String> parseBatchTranslation(String translated) {
        Map<Integer, String> parts = new HashMap<>();
        if (TextUtils.isEmpty(translated)) {
            return parts;
        }

        Matcher matcher = BATCH_MARKER_PATTERN.matcher(translated);
        int previousIndex = -1;
        int contentStart = -1;

        while (matcher.find()) {
            if (previousIndex >= 0 && contentStart >= 0) {
                String part = cleanBatchPart(
                        translated.substring(contentStart, matcher.start())
                );
                if (!TextUtils.isEmpty(part)) {
                    parts.put(previousIndex, part);
                }
            }

            try {
                previousIndex = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                previousIndex = -1;
            }
            contentStart = matcher.end();
        }

        if (previousIndex >= 0 && contentStart >= 0) {
            String part = cleanBatchPart(translated.substring(contentStart));
            if (!TextUtils.isEmpty(part)) {
                parts.put(previousIndex, part);
            }
        }

        return parts;
    }

    private String cleanBatchPart(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .replaceAll("^[\\s:：\\-–—]+", "")
                .replaceAll("[\\s:：\\-–—]+$", "")
                .trim();
    }

    private void cacheTranslation(String source, String translated) {
        if (TextUtils.isEmpty(source) || TextUtils.isEmpty(translated)) {
            return;
        }
        if (translationCache.size() > 500) {
            translationCache.clear();
        }
        translationCache.put(source, translated);
    }

    private PreparedSource prepareSourceForTranslation(String source) {
        List<GlossaryTerm> glossaryTerms = new ArrayList<>();

        for (Map.Entry<String, String> entry : customGlossary.entrySet()) {
            if (!TextUtils.isEmpty(entry.getKey())
                    && !TextUtils.isEmpty(entry.getValue())) {
                glossaryTerms.add(new GlossaryTerm(
                        entry.getKey().trim(),
                        entry.getValue().trim(),
                        true
                ));
            }
        }

        for (String chinese : PROTECTED_GAME_TERMS) {
            String vietnamese = exactGameTranslation(chinese);
            if (!TextUtils.isEmpty(vietnamese)) {
                glossaryTerms.add(new GlossaryTerm(
                        chinese,
                        vietnamese,
                        false
                ));
            }
        }

        glossaryTerms.sort((first, second) -> {
            if (first.custom != second.custom) {
                return first.custom ? -1 : 1;
            }
            return Integer.compare(
                    second.chinese.length(),
                    first.chinese.length()
            );
        });

        String prepared = source;
        List<String> replacements = new ArrayList<>();

        for (GlossaryTerm term : glossaryTerms) {
            if (!prepared.contains(term.chinese)) {
                continue;
            }

            int tokenIndex = replacements.size();
            String token = "BBDTERM" + tokenIndex + "X";
            prepared = prepared.replace(term.chinese, " " + token + " ");
            replacements.add(term.vietnamese);
        }

        prepared = prepared
                .replaceAll("[\\t ]+", " ")
                .replaceAll(" ?\\n ?", "\n")
                .trim();
        return new PreparedSource(prepared, replacements);
    }

    private String restoreProtectedTerms(
            String translated,
            PreparedSource preparedSource
    ) {
        String restored = translated == null ? "" : translated;

        for (int index = 0; index < preparedSource.replacements.size(); index++) {
            String pattern = "(?i)BBD\\s*TERM\\s*"
                    + index
                    + "\\s*X";
            restored = restored.replaceAll(
                    pattern,
                    Matcher.quoteReplacement(
                            preparedSource.replacements.get(index)
                    )
            );
        }

        return restored
                .replaceAll("\\s+([,.;:!?])", "$1")
                .replaceAll("[\\t ]{2,}", " ")
                .trim();
    }

    private int translationQualityScore(String source, String translated) {
        if (TextUtils.isEmpty(translated)) {
            return -1000;
        }

        int score = 100;
        String normalizedSource = normalizeGlossaryKey(source);
        String normalizedTranslation = normalizeGlossaryKey(translated);

        if (!normalizedSource.isEmpty()
                && normalizedSource.equalsIgnoreCase(normalizedTranslation)) {
            score -= 90;
        }

        int remainingChinese = 0;
        for (int index = 0; index < translated.length(); index++) {
            char character = translated.charAt(index);
            if ((character >= '\u3400' && character <= '\u4DBF')
                    || (character >= '\u4E00' && character <= '\u9FFF')
                    || (character >= '\uF900' && character <= '\uFAFF')) {
                remainingChinese++;
            }
        }
        score -= Math.min(80, remainingChinese * 12);

        if (translated.toUpperCase().contains("BBDTERM")
                || translated.toUpperCase().contains("BBD_")) {
            score -= 100;
        }

        int sourceLength = Math.max(1, normalizedSource.length());
        int translatedLength = normalizedTranslation.length();
        if (translatedLength > sourceLength * 7 + 45) {
            score -= 35;
        }
        if (sourceLength >= 5 && translatedLength <= 1) {
            score -= 45;
        }

        Matcher numberMatcher = NUMBER_TOKEN_PATTERN.matcher(source);
        while (numberMatcher.find()) {
            if (!translated.contains(numberMatcher.group())) {
                score -= 18;
            }
        }

        return score;
    }

    private String translateOnlineWithRetry(String source) throws Exception {
        try {
            return translateOnline(source);
        } catch (Exception firstError) {
            if (cleaningUp) {
                throw firstError;
            }
            return translateOnline(source);
        }
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

        String customTerm = exactCustomTranslation(source);
        if (!TextUtils.isEmpty(customTerm)) {
            return new RegionTranslation(region, customTerm);
        }

        String fixedTerm = exactGameTranslation(source);
        if (!TextUtils.isEmpty(fixedTerm)) {
            return new RegionTranslation(region, fixedTerm);
        }

        String cached = translationCache.get(source);
        if (!TextUtils.isEmpty(cached)) {
            return new RegionTranslation(
                    region,
                    compactTranslation(source, cached)
            );
        }

        PreparedSource preparedSource = prepareSourceForTranslation(source);
        String bestTranslation = null;
        int bestScore = -1000;

        try {
            String translated = translateOnlineWithRetry(preparedSource.text);
            String restored = restoreProtectedTerms(
                    translated,
                    preparedSource
            );
            String candidate = compactTranslation(source, restored);
            bestTranslation = candidate;
            bestScore = translationQualityScore(source, candidate);
        } catch (Exception ignored) {
            // Thử lại bằng câu gốc ở dưới nếu bản có khóa thuật ngữ bị lỗi.
        }

        if (!preparedSource.text.equals(source) && bestScore < 70) {
            try {
                String rawCandidate = compactTranslation(
                        source,
                        translateOnlineWithRetry(source)
                );
                int rawScore = translationQualityScore(source, rawCandidate);
                if (rawScore > bestScore) {
                    bestTranslation = rawCandidate;
                    bestScore = rawScore;
                }
            } catch (Exception ignored) {
                // Giữ ứng viên có khóa thuật ngữ nếu yêu cầu dự phòng thất bại.
            }
        }

        if (cleaningUp || generation != captureSequence
                || TextUtils.isEmpty(bestTranslation)
                || bestScore < 15) {
            return new RegionTranslation(region, null);
        }

        cacheTranslation(source, bestTranslation);
        return new RegionTranslation(region, bestTranslation);
    }

    private String translateOnline(String source) throws Exception {
        String cached = translationCache.get(source);
        if (!TextUtils.isEmpty(cached)) {
            return cached;
        }

        HttpURLConnection connection = null;
        boolean responseConsumed = false;
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
                    "Mozilla/5.0 (Linux; Android) BongBongDich/1.10.2"
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
                responseConsumed = true;
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

            cacheTranslation(source, result);
            return result;
        } finally {
            if (connection != null && !responseConsumed) {
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
        raiseTranslationControls();
        showBubble();
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
        updateFrozenScreenBar(displayedCount, failedCount);
        raiseTranslationControls();
        showBubble();

        if (displayedCount == 0) {
            showToast("Dịch thất bại. Chạm × để đóng rồi thử lại.");
        } else if (failedCount > 0) {
            showToast("Đã tạo ảnh dịch; còn " + failedCount + " ô bị lỗi.");
        } else {
            showToast("Đã dịch toàn màn hình. Chạm × để trở lại game.");
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
            case "碎片":
                return "Mảnh";
            case "羁绊":
                return "Duyên";
            case "阵营":
                return "Phe";
            case "漩涡鸣人":
                return "Naruto Uzumaki";
            case "鸣人":
                return "Naruto";
            case "鸣人仙人":
            case "仙人鸣人":
                return "Naruto Tiên Nhân";
            case "九尾鸣人":
                return "Naruto Cửu Vĩ";
            case "六道鸣人":
                return "Naruto Lục Đạo";
            case "宇智波佐助":
                return "Sasuke Uchiha";
            case "佐助":
                return "Sasuke";
            case "轮回眼佐助":
                return "Sasuke Luân Hồi Nhãn";
            case "春野樱":
                return "Sakura Haruno";
            case "小樱":
                return "Sakura";
            case "旗木卡卡西":
            case "卡卡西":
                return "Kakashi";
            case "宇智波鼬":
            case "鼬":
                return "Itachi";
            case "宇智波带土":
            case "带土":
                return "Obito";
            case "宇智波斑":
            case "斑":
                return "Madara";
            case "日向雏田":
            case "雏田":
                return "Hinata";
            case "纲手":
                return "Tsunade";
            case "自来也":
                return "Jiraiya";
            case "大蛇丸":
                return "Orochimaru";
            case "我爱罗":
                return "Gaara";
            case "六道":
                return "Lục Đạo";
            case "火影":
                return "Hokage";
            case "忍者":
                return "Ninja";
            case "忍术":
                return "Nhẫn thuật";
            case "尾兽":
                return "Vĩ thú";
            case "通灵兽":
                return "Thông linh thú";
            case "查克拉":
                return "Chakra";
            case "忍界":
                return "Nhẫn giới";
            default:
                return null;
        }
    }

    private String compactTranslation(String source, String translated) {
        String customExact = exactCustomTranslation(source);
        if (!TextUtils.isEmpty(customExact)) {
            return customExact;
        }

        String exact = exactGameTranslation(source);
        if (exact != null) {
            return exact;
        }

        String result = translated.trim()
                .replaceAll("^\\s*\\[", "")
                .replaceAll("\\]\\s*$", "");

        if (sourceUsesCustomGlossary(source)) {
            return result
                    .replace("「", "")
                    .replace("」", "")
                    .trim();
        }

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
        translationLayer.setFocusableInTouchMode(true);
        translationLayer.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                handleBubbleTap();
            }
            return true;
        });

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
        translationLayerParams.alpha = 1f;

        windowManager.addView(translationLayer, translationLayerParams);
    }

    private void showFrozenScreen(Bitmap bitmap) {
        if (translationLayer == null || bitmap == null || bitmap.isRecycled()) {
            return;
        }

        frozenScreenBitmap = bitmap;
        frozenScreenView = new ImageView(this);
        frozenScreenView.setScaleType(ImageView.ScaleType.FIT_XY);
        frozenScreenView.setImageBitmap(bitmap);
        frozenScreenView.setContentDescription("Ảnh màn hình đang được dịch");

        FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        translationLayer.addView(frozenScreenView, imageParams);

        createFrozenScreenBar();
        translationsVisible = true;
        translationLayer.setVisibility(View.VISIBLE);
        translationLayer.setClickable(true);
        translationLayer.setOnTouchListener((view, event) -> true);
        setTranslationLayerTouchBlocking(true);
        updateBubbleVisual();
        raiseTranslationControls();
        showBubble();

        int generation = captureSequence;
        mainHandler.postDelayed(() -> {
            if (!cleaningUp
                    && generation == captureSequence
                    && translationsVisible) {
                showBubble();
                raiseTranslationControls();
            }
        }, 180);
    }

    private void createFrozenScreenBar() {
        if (translationLayer == null || frozenScreenBar != null) {
            return;
        }

        frozenScreenBar = new TextView(this);
        frozenScreenBar.setText("✦  Đang nhận dạng và dịch…");
        frozenScreenBar.setTextColor(Color.parseColor("#172033"));
        frozenScreenBar.setTextSize(14);
        frozenScreenBar.setTypeface(
                Typeface.create("sans-serif-medium", Typeface.NORMAL)
        );
        frozenScreenBar.setGravity(Gravity.CENTER);
        frozenScreenBar.setIncludeFontPadding(false);
        frozenScreenBar.setPadding(dp(18), 0, dp(18), 0);
        frozenScreenBar.setElevation(dp(14));
        frozenScreenBar.setOnClickListener(view -> handleBubbleTap());

        GradientDrawable barBackground = new GradientDrawable();
        barBackground.setShape(GradientDrawable.RECTANGLE);
        barBackground.setColor(Color.parseColor("#F5FFFFFF"));
        barBackground.setCornerRadius(dp(30));
        barBackground.setStroke(dp(1), Color.parseColor("#22000000"));
        frozenScreenBar.setBackground(barBackground);

        int barWidth = Math.min(dp(330), Math.max(dp(250), screenWidth - dp(36)));
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                barWidth,
                dp(56),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
        );
        barParams.bottomMargin = dp(26);
        translationLayer.addView(frozenScreenBar, barParams);
    }

    private void raiseTranslationControls() {
        if (frozenScreenBar != null) {
            frozenScreenBar.bringToFront();
        }
    }

    private void updateFrozenScreenBar(int displayedCount, int failedCount) {
        if (frozenScreenBar == null) {
            return;
        }

        if (displayedCount <= 0) {
            frozenScreenBar.setText("Không dịch được  •  Chạm để đóng ×");
        } else if (failedCount > 0) {
            frozenScreenBar.setText(
                    "✦  Trung → Việt  •  Thiếu " + failedCount + " ô  •  Đóng ×"
            );
        } else {
            frozenScreenBar.setText("✦  Trung → Việt  •  Chạm để đóng ×");
        }
        frozenScreenBar.bringToFront();
    }

    private void updateFrozenScreenProgress(
            int completedCount,
            int totalCount,
            int displayedCount
    ) {
        if (frozenScreenBar == null || totalCount <= 0) {
            return;
        }

        frozenScreenBar.setText(
                "✦  Đang dịch "
                        + completedCount
                        + "/"
                        + totalCount
                        + "  •  Đã hiện "
                        + displayedCount
                        + " ô"
        );
        frozenScreenBar.bringToFront();
    }

    private void setTranslationLayerTouchBlocking(boolean blocking) {
        if (translationLayer == null || translationLayerParams == null) {
            return;
        }

        if (blocking) {
            translationLayerParams.flags &=
                    ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            translationLayerParams.flags &=
                    ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            translationLayer.setFocusable(true);
            translationLayer.setFocusableInTouchMode(true);
        } else {
            translationLayerParams.flags |=
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            translationLayerParams.flags |=
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            translationLayer.clearFocus();
            translationLayer.setFocusable(false);
        }

        try {
            windowManager.updateViewLayout(translationLayer, translationLayerParams);
            if (blocking) {
                translationLayer.post(translationLayer::requestFocus);
            }
        } catch (Exception ignored) {
            // Lớp phủ có thể đang được tạo lại hoặc dịch vụ đang dừng.
        }
    }

    private void releaseFrozenScreen() {
        if (frozenScreenView != null) {
            frozenScreenView.setImageDrawable(null);
            frozenScreenView = null;
        }
        frozenScreenBar = null;

        if (frozenScreenBitmap != null && !frozenScreenBitmap.isRecycled()) {
            frozenScreenBitmap.recycle();
        }
        frozenScreenBitmap = null;
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

        OverlayStyle overlayStyle = sampleOverlayStyle(displayBounds);
        TextView label = new TextView(this);
        label.setText(translated);
        label.setTextColor(overlayStyle.textColor);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setGravity(region.lineCount > 1
                ? Gravity.START | Gravity.CENTER_VERTICAL
                : Gravity.CENTER);
        label.setPadding(dp(1), 0, dp(1), 0);
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
        background.setColor(overlayStyle.backgroundColor);
        background.setCornerRadius(dp(2));
        label.setBackground(background);

        FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(
                displayBounds.width(),
                displayBounds.height()
        );
        labelParams.leftMargin = displayBounds.left;
        labelParams.topMargin = displayBounds.top;

        translationLayer.addView(label, labelParams);
        raiseTranslationControls();
        placedTranslationBounds.add(new Rect(displayBounds));
        translationsVisible = true;
        translationLayer.setVisibility(View.VISIBLE);
        updateBubbleVisual();
        return true;
    }

    private OverlayStyle sampleOverlayStyle(Rect overlayBounds) {
        Bitmap bitmap = frozenScreenBitmap;
        if (bitmap == null || bitmap.isRecycled() || translationLayer == null) {
            return new OverlayStyle(
                    Color.parseColor("#F21A1722"),
                    Color.WHITE
            );
        }

        int overlayWidth = translationLayer.getWidth() > 0
                ? translationLayer.getWidth()
                : screenWidth;
        int overlayHeight = translationLayer.getHeight() > 0
                ? translationLayer.getHeight()
                : screenHeight;
        float scaleX = bitmap.getWidth() / (float) Math.max(1, overlayWidth);
        float scaleY = bitmap.getHeight() / (float) Math.max(1, overlayHeight);

        int left = clamp(
                Math.round(overlayBounds.left * scaleX),
                0,
                bitmap.getWidth() - 1
        );
        int top = clamp(
                Math.round(overlayBounds.top * scaleY),
                0,
                bitmap.getHeight() - 1
        );
        int right = clamp(
                Math.round(overlayBounds.right * scaleX) - 1,
                left,
                bitmap.getWidth() - 1
        );
        int bottom = clamp(
                Math.round(overlayBounds.bottom * scaleY) - 1,
                top,
                bitmap.getHeight() - 1
        );

        int marginX = Math.max(2, Math.round(dp(3) * scaleX));
        int marginY = Math.max(2, Math.round(dp(3) * scaleY));
        int sampleLeft = clamp(left - marginX, 0, bitmap.getWidth() - 1);
        int sampleRight = clamp(right + marginX, 0, bitmap.getWidth() - 1);
        int sampleTop = clamp(top - marginY, 0, bitmap.getHeight() - 1);
        int sampleBottom = clamp(bottom + marginY, 0, bitmap.getHeight() - 1);
        int stepX = Math.max(1, (right - left + 1) / 10);
        int stepY = Math.max(1, (bottom - top + 1) / 8);

        long red = 0;
        long green = 0;
        long blue = 0;
        int samples = 0;

        for (int x = left; x <= right; x += stepX) {
            int topColor = bitmap.getPixel(x, sampleTop);
            int bottomColor = bitmap.getPixel(x, sampleBottom);
            red += Color.red(topColor) + Color.red(bottomColor);
            green += Color.green(topColor) + Color.green(bottomColor);
            blue += Color.blue(topColor) + Color.blue(bottomColor);
            samples += 2;
        }
        for (int y = top; y <= bottom; y += stepY) {
            int leftColor = bitmap.getPixel(sampleLeft, y);
            int rightColor = bitmap.getPixel(sampleRight, y);
            red += Color.red(leftColor) + Color.red(rightColor);
            green += Color.green(leftColor) + Color.green(rightColor);
            blue += Color.blue(leftColor) + Color.blue(rightColor);
            samples += 2;
        }

        if (samples <= 0) {
            return new OverlayStyle(
                    Color.parseColor("#F21A1722"),
                    Color.WHITE
            );
        }

        int averageRed = clamp((int) (red / samples), 0, 255);
        int averageGreen = clamp((int) (green / samples), 0, 255);
        int averageBlue = clamp((int) (blue / samples), 0, 255);
        int backgroundColor = Color.rgb(averageRed, averageGreen, averageBlue);
        double luminance = 0.299d * averageRed
                + 0.587d * averageGreen
                + 0.114d * averageBlue;
        int textColor = luminance >= 148d
                ? Color.parseColor("#352F2B")
                : Color.WHITE;
        return new OverlayStyle(backgroundColor, textColor);
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
        releaseFrozenScreen();

        if (translationLayer != null) {
            translationLayer.removeAllViews();
            translationLayer.setVisibility(View.INVISIBLE);
            translationLayer.setClickable(false);
            translationLayer.setOnTouchListener(null);
            setTranslationLayerTouchBlocking(false);
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
        }, 120);
    }

    private void createDismissTarget() {
        if (dismissTargetView != null) {
            return;
        }

        dismissTargetView = new TextView(this);
        dismissTargetView.setText("×  Kéo vào đây để tắt");
        dismissTargetView.setTextColor(Color.WHITE);
        dismissTargetView.setTextSize(14);
        dismissTargetView.setTypeface(Typeface.DEFAULT_BOLD);
        dismissTargetView.setGravity(Gravity.CENTER);
        dismissTargetView.setIncludeFontPadding(false);
        dismissTargetView.setPadding(dp(16), 0, dp(16), 0);
        dismissTargetView.setVisibility(View.INVISIBLE);
        dismissTargetView.setElevation(dp(12));

        dismissTargetBackground = new GradientDrawable();
        dismissTargetBackground.setShape(GradientDrawable.RECTANGLE);
        dismissTargetBackground.setCornerRadius(dp(30));
        dismissTargetBackground.setColor(Color.parseColor("#E02A304A"));
        dismissTargetBackground.setStroke(dp(1), Color.parseColor("#66FFFFFF"));
        dismissTargetView.setBackground(dismissTargetBackground);

        dismissTargetParams = new WindowManager.LayoutParams(
                dp(220),
                dp(58),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        dismissTargetParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        dismissTargetParams.y = dp(30);

        windowManager.addView(dismissTargetView, dismissTargetParams);
    }

    private void showDismissTarget() {
        if (dismissTargetView == null) {
            return;
        }
        if (dismissTargetView.getVisibility() != View.VISIBLE) {
            dismissTargetView.setVisibility(View.VISIBLE);
        }
    }

    private void hideDismissTarget() {
        bubbleOverDismissTarget = false;
        if (dismissTargetView != null) {
            updateDismissTargetVisual(false);
            dismissTargetView.setVisibility(View.INVISIBLE);
        }
    }

    private void updateDismissTargetVisual(boolean active) {
        if (dismissTargetView == null || dismissTargetBackground == null) {
            return;
        }
        bubbleOverDismissTarget = active;
        dismissTargetView.setText(active
                ? "✓  Thả tay để tắt"
                : "×  Kéo vào đây để tắt");
        dismissTargetBackground.setColor(Color.parseColor(
                active ? "#F0445E" : "#E02A304A"
        ));
        dismissTargetBackground.setStroke(
                dp(active ? 2 : 1),
                Color.parseColor(active ? "#FFFFFFFF" : "#66FFFFFF")
        );
        dismissTargetView.setScaleX(active ? 1.08f : 1f);
        dismissTargetView.setScaleY(active ? 1.08f : 1f);
        dismissTargetView.invalidate();
    }

    private boolean isBubbleInsideDismissTarget() {
        if (bubbleParams == null || dismissTargetParams == null) {
            return false;
        }

        int targetWidth = dismissTargetView != null && dismissTargetView.getWidth() > 0
                ? dismissTargetView.getWidth()
                : dismissTargetParams.width;
        int targetHeight = dismissTargetView != null && dismissTargetView.getHeight() > 0
                ? dismissTargetView.getHeight()
                : dismissTargetParams.height;
        int targetLeft = (screenWidth - targetWidth) / 2;
        int targetTop = screenHeight - dismissTargetParams.y - targetHeight;
        if (dismissTargetView != null && dismissTargetView.getVisibility() == View.VISIBLE) {
            int[] targetLocation = new int[]{targetLeft, targetTop};
            dismissTargetView.getLocationOnScreen(targetLocation);
            targetLeft = targetLocation[0];
            targetTop = targetLocation[1];
        }

        Rect target = new Rect(
                targetLeft - dp(24),
                targetTop - dp(24),
                targetLeft + targetWidth + dp(24),
                targetTop + targetHeight + dp(18)
        );
        int bubbleCenterX = bubbleParams.x + bubbleParams.width / 2;
        int bubbleCenterY = bubbleParams.y + bubbleParams.height / 2;
        return target.contains(bubbleCenterX, bubbleCenterY);
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
        bubbleBackground.setOrientation(GradientDrawable.Orientation.TL_BR);
        bubbleBackground.setStroke(dp(2), Color.parseColor("#B3FFFFFF"));
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
            bubbleBackground.setColors(new int[]{
                    Color.parseColor("#FB7185"),
                    Color.parseColor("#E11D48")
            });
        } else if (busy) {
            bubbleView.setText("…");
            bubbleView.setContentDescription("Chạm để hủy dịch");
            bubbleBackground.setColors(new int[]{
                    Color.parseColor("#F59E0B"),
                    Color.parseColor("#EA580C")
            });
        } else {
            bubbleView.setText("译");
            bubbleView.setContentDescription("Chạm để dịch, kéo để di chuyển");
            bubbleBackground.setColors(new int[]{
                    Color.parseColor("#8B5CF6"),
                    Color.parseColor("#22D3EE")
            });
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
                        hideDismissTarget();
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
                            showDismissTarget();
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
                        if (moved) {
                            updateDismissTargetVisual(isBubbleInsideDismissTarget());
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        boolean shouldStop = moved && isBubbleInsideDismissTarget();
                        hideDismissTarget();
                        if (shouldStop) {
                            showToast("Đã tắt bong bóng dịch.");
                            stopSelf();
                            return true;
                        }
                        if (!moved) {
                            handleBubbleTap();
                        }
                        return true;

                    case MotionEvent.ACTION_CANCEL:
                        hideDismissTarget();
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
        if (bubbleView == null || bubbleParams == null || cleaningUp) {
            return;
        }

        updateBubbleVisual();
        bubbleView.setVisibility(View.VISIBLE);
        bringBubbleWindowToFront();
    }

    private void bringBubbleWindowToFront() {
        try {
            if (bubbleView.isAttachedToWindow()) {
                windowManager.removeViewImmediate(bubbleView);
            }
            windowManager.addView(bubbleView, bubbleParams);
        } catch (Exception ignored) {
            // Nếu lớp phủ đang đổi kích thước, thử gắn lại mà không làm dừng app.
            try {
                if (!bubbleView.isAttachedToWindow()) {
                    windowManager.addView(bubbleView, bubbleParams);
                } else {
                    windowManager.updateViewLayout(bubbleView, bubbleParams);
                }
            } catch (Exception ignoredAgain) {
                // Dịch vụ có thể đang dừng.
            }
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
                .setContentText("译: dịch • ×: ẩn • kéo xuống để tắt bong bóng")
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

        releaseFrozenScreen();
        if (translationLayer != null) {
            try {
                windowManager.removeView(translationLayer);
            } catch (Exception ignored) {
                // View đã được gỡ.
            }
            translationLayer = null;
        }

        if (dismissTargetView != null) {
            try {
                windowManager.removeView(dismissTargetView);
            } catch (Exception ignored) {
                // View đã được gỡ.
            }
            dismissTargetView = null;
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

    private static final class GlossaryTerm {
        final String chinese;
        final String vietnamese;
        final boolean custom;

        GlossaryTerm(
                String chinese,
                String vietnamese,
                boolean custom
        ) {
            this.chinese = chinese;
            this.vietnamese = vietnamese;
            this.custom = custom;
        }
    }

    private static final class PreparedSource {
        final String text;
        final List<String> replacements;

        PreparedSource(String text, List<String> replacements) {
            this.text = text;
            this.replacements = replacements;
        }
    }

    private static final class RegionTranslation {
        final OcrRegion region;
        final String translated;

        RegionTranslation(OcrRegion region, String translated) {
            this.region = region;
            this.translated = translated;
        }
    }

    private static final class OverlayStyle {
        final int backgroundColor;
        final int textColor;

        OverlayStyle(int backgroundColor, int textColor) {
            this.backgroundColor = backgroundColor;
            this.textColor = textColor;
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
