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

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

    private WindowManager windowManager;
    private TextView bubbleView;
    private GradientDrawable bubbleBackground;
    private WindowManager.LayoutParams bubbleParams;

    private FrameLayout translationLayer;
    private WindowManager.LayoutParams translationLayerParams;
    private final List<Rect> placedTranslationBounds = new ArrayList<>();
    private boolean translationsVisible;

    private MediaProjection mediaProjection;
    private MediaProjection.Callback mediaProjectionCallback;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;

    private Handler mainHandler;
    private HandlerThread captureThread;
    private Handler captureHandler;

    private TextRecognizer textRecognizer;
    private Translator translator;
    private volatile boolean modelReady;
    private volatile boolean captureNextFrame;
    private volatile boolean busy;
    private boolean cleaningUp;
    private int captureSequence;

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

        textRecognizer = TextRecognition.getClient(
                new ChineseTextRecognizerOptions.Builder().build()
        );

        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.CHINESE)
                .setTargetLanguage(TranslateLanguage.VIETNAMESE)
                .build();
        translator = Translation.getClient(options);

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

        warmUpTranslationModel();
        showToast("Chạm 译 để dịch, chạm × để tắt bản dịch.");
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

        if (modelReady) {
            performRegionTranslations(regions);
            return;
        }

        showToast("Đang tải bộ dịch Trung–Việt lần đầu…");
        DownloadConditions conditions = new DownloadConditions.Builder().build();
        translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(unused -> {
                    modelReady = true;
                    performRegionTranslations(regions);
                })
                .addOnFailureListener(exception ->
                        showFailure("Không tải được bộ dịch. Hãy kiểm tra mạng rồi thử lại."));
    }

    private void performRegionTranslations(List<OcrRegion> regions) {
        showToast("Đang dịch " + regions.size() + " vùng chữ…");

        int generation = captureSequence;
        List<Task<String>> tasks = new ArrayList<>();
        AtomicInteger displayedCount = new AtomicInteger(0);

        for (OcrRegion region : regions) {
            String source = region.source.length() > 1000
                    ? region.source.substring(0, 1000)
                    : region.source;

            Task<String> task = translator.translate(source);
            tasks.add(task);

            task.addOnSuccessListener(translated -> {
                if (cleaningUp || generation != captureSequence) {
                    return;
                }

                if (translated != null && !translated.trim().isEmpty()) {
                    String compact = compactTranslation(region.source, translated);
                    if (addTranslationAtPosition(region, compact)) {
                        displayedCount.incrementAndGet();
                    }
                }
            });
        }

        Tasks.whenAllComplete(tasks).addOnCompleteListener(unused -> {
            if (cleaningUp || generation != captureSequence) {
                return;
            }

            busy = false;
            updateBubbleVisual();

            if (displayedCount.get() == 0) {
                showToast("Không dịch được nội dung. Hãy thử lại.");
            } else {
                showToast("Đã dịch. Chạm × để ẩn.");
            }
        });
    }

    private String compactTranslation(String source, String translated) {
        String key = source.replaceAll("[\\[\\]【】（）()\\s]", "");
        switch (key) {
            case "活动":
                return "Sự kiện";
            case "公告":
                return "Tin";
            case "领取":
                return "Nhận";
            case "充值":
                return "Nạp";
            case "购买":
                return "Mua";
            case "返回":
                return "Về";
            case "确定":
            case "确认":
                return "OK";
            case "取消":
                return "Hủy";
            default:
                return translated.trim()
                        .replaceAll("^\\[\\s*", "")
                        .replaceAll("\\s*\\]$", "");
        }
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
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
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

        if (overlapsExistingRegion(mappedBounds)) {
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
        label.setMaxLines(Math.max(1, region.lineCount + 1));
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setAutoSizeTextTypeUniformWithConfiguration(
                6,
                18,
                1,
                TypedValue.COMPLEX_UNIT_SP
        );

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.parseColor("#F21A1722"));
        background.setCornerRadius(dp(4));
        background.setStroke(dp(1), Color.parseColor("#8F7BFF"));
        label.setBackground(background);

        FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(
                mappedBounds.width(),
                mappedBounds.height()
        );
        labelParams.leftMargin = mappedBounds.left;
        labelParams.topMargin = mappedBounds.top;

        translationLayer.addView(label, labelParams);
        placedTranslationBounds.add(new Rect(mappedBounds));
        translationsVisible = true;
        translationLayer.setVisibility(View.VISIBLE);
        updateBubbleVisual();
        return true;
    }

    private Rect mapRegionToOverlay(OcrRegion region) {
        int overlayWidth = translationLayer.getWidth() > 0
                ? translationLayer.getWidth()
                : screenWidth;
        int overlayHeight = translationLayer.getHeight() > 0
                ? translationLayer.getHeight()
                : screenHeight;

        float captureScale = Math.min(
                region.imageWidth / (float) Math.max(1, overlayWidth),
                region.imageHeight / (float) Math.max(1, overlayHeight)
        );
        if (captureScale <= 0f) {
            captureScale = 1f;
        }

        float renderedWidth = overlayWidth * captureScale;
        float renderedHeight = overlayHeight * captureScale;
        float captureOffsetX = (region.imageWidth - renderedWidth) / 2f;
        float captureOffsetY = (region.imageHeight - renderedHeight) / 2f;

        int[] layerLocation = new int[]{0, 0};
        translationLayer.getLocationOnScreen(layerLocation);

        int left = Math.round((region.bounds.left - captureOffsetX) / captureScale)
                - layerLocation[0];
        int top = Math.round((region.bounds.top - captureOffsetY) / captureScale)
                - layerLocation[1];
        int right = Math.round((region.bounds.right - captureOffsetX) / captureScale)
                - layerLocation[0];
        int bottom = Math.round((region.bounds.bottom - captureOffsetY) / captureScale)
                - layerLocation[1];

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

    private void warmUpTranslationModel() {
        DownloadConditions conditions = new DownloadConditions.Builder().build();
        translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(unused -> modelReady = true);
    }

    private void updateScreenBounds() {
        densityDpi = getResources().getConfiguration().densityDpi;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowMetrics metrics = windowManager.getMaximumWindowMetrics();
            Rect bounds = metrics.getBounds();
            screenWidth = bounds.width();
            screenHeight = bounds.height();
        } else {
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;
            densityDpi = metrics.densityDpi;
        }
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

        int oldWidth = screenWidth;
        int oldHeight = screenHeight;
        updateScreenBounds();

        if (oldWidth == screenWidth && oldHeight == screenHeight) {
            return;
        }

        captureSequence++;
        busy = false;
        captureNextFrame = false;
        clearTranslations();

        ImageReader oldReader = imageReader;
        ImageReader newReader = createImageReader(screenWidth, screenHeight);

        try {
            virtualDisplay.resize(screenWidth, screenHeight, densityDpi);
            virtualDisplay.setSurface(newReader.getSurface());
            imageReader = newReader;

            if (oldReader != null) {
                oldReader.setOnImageAvailableListener(null, null);
                oldReader.close();
            }

            if (bubbleView != null) {
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
                windowManager.updateViewLayout(bubbleView, bubbleParams);
            }
        } catch (Exception exception) {
            newReader.setOnImageAvailableListener(null, null);
            newReader.close();
            imageReader = oldReader;
            showToast("Xoay màn hình chưa thành công, hãy bật lại bong bóng.");
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
        if (translator != null) {
            translator.close();
        }

        if (captureThread != null) {
            captureThread.quitSafely();
        }

        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
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
