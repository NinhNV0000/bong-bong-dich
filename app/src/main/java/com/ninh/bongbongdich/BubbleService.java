package com.ninh.bongbongdich;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.nio.ByteBuffer;

public class BubbleService extends Service {

    public static final String ACTION_START = "com.ninh.bongbongdich.START";
    public static final String ACTION_STOP = "com.ninh.bongbongdich.STOP";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String PREFS_NAME = "bubble_state";
    public static final String KEY_RUNNING = "running";

    private static final String CHANNEL_ID = "bubble_translate_channel";
    private static final int NOTIFICATION_ID = 2409;

    private WindowManager windowManager;
    private TextView bubbleView;
    private WindowManager.LayoutParams bubbleParams;

    private View panelView;
    private WindowManager.LayoutParams panelParams;
    private TextView sourceTextView;
    private TextView translatedTextView;
    private String currentTranslation = "";
    private boolean panelAdded;

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

        createBubble();
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_RUNNING, true)
                .apply();

        warmUpTranslationModel();
        showToast("Đã bật bong bóng dịch. Chạm 译 để dịch.");
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
            return;
        }

        InputImage inputImage = InputImage.fromBitmap(bitmap, 0);
        textRecognizer.process(inputImage)
                .addOnSuccessListener(result -> {
                    if (cleaningUp) {
                        return;
                    }

                    String source = cleanRecognizedText(result.getText());
                    if (source.isEmpty()) {
                        busy = false;
                        showPanel(
                                "Không tìm thấy chữ Trung",
                                "Hãy đóng bảng này, để chữ trong game hiện rõ rồi chạm bong bóng lần nữa."
                        );
                        return;
                    }

                    translateText(source);
                })
                .addOnFailureListener(exception ->
                        showFailure("Không nhận dạng được chữ: " + readableError(exception)))
                .addOnCompleteListener(task -> {
                    if (!bitmap.isRecycled()) {
                        bitmap.recycle();
                    }
                });
    }

    private String cleanRecognizedText(String text) {
        if (text == null) {
            return "";
        }
        return text.trim()
                .replaceAll("[\\t ]+", " ")
                .replaceAll("\\n{3,}", "\n\n");
    }

    private void translateText(String source) {
        String textForTranslation = source.length() > 5000
                ? source.substring(0, 5000)
                : source;

        if (modelReady) {
            performTranslation(source, textForTranslation);
            return;
        }

        showPanel(source, "Đang tải bộ dịch Trung–Việt lần đầu…\nHãy giữ kết nối mạng.");
        DownloadConditions conditions = new DownloadConditions.Builder().build();
        translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(unused -> {
                    modelReady = true;
                    performTranslation(source, textForTranslation);
                })
                .addOnFailureListener(exception ->
                        showFailureWithSource(
                                source,
                                "Không tải được bộ dịch. Hãy kiểm tra mạng rồi thử lại.\n"
                                        + readableError(exception)
                        ));
    }

    private void performTranslation(String source, String textForTranslation) {
        showPanel(source, "Đang dịch…");
        translator.translate(textForTranslation)
                .addOnSuccessListener(translated -> {
                    busy = false;
                    showPanel(source, translated == null || translated.trim().isEmpty()
                            ? "Không có kết quả dịch."
                            : translated.trim());
                })
                .addOnFailureListener(exception ->
                        showFailureWithSource(
                                source,
                                "Dịch thất bại. Hãy thử lại.\n" + readableError(exception)
                        ));
    }

    private void warmUpTranslationModel() {
        DownloadConditions conditions = new DownloadConditions.Builder().build();
        translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(unused -> modelReady = true);
    }

    private void requestCapture() {
        if (busy) {
            showToast("Đang xử lý bản dịch trước…");
            return;
        }
        if (imageReader == null || virtualDisplay == null) {
            showFailure("Chưa sẵn sàng chụp màn hình. Hãy bật lại bong bóng.");
            return;
        }

        busy = true;
        captureNextFrame = false;
        int sequence = ++captureSequence;

        hidePanel();
        hideBubble();
        showToast("Đang đọc chữ trên màn hình…");

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
        }, 260);
    }

    private void createBubble() {
        if (bubbleView != null) {
            return;
        }

        bubbleView = new TextView(this);
        bubbleView.setText("译");
        bubbleView.setTextColor(Color.WHITE);
        bubbleView.setTextSize(25);
        bubbleView.setTypeface(Typeface.DEFAULT_BOLD);
        bubbleView.setGravity(Gravity.CENTER);
        bubbleView.setIncludeFontPadding(false);
        bubbleView.setContentDescription("Chạm để dịch, kéo để di chuyển");

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(Color.parseColor("#6847F5"));
        background.setStroke(dp(2), Color.parseColor("#66FFFFFF"));
        bubbleView.setBackground(background);
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

        try {
            windowManager.addView(bubbleView, bubbleParams);
        } catch (Exception exception) {
            bubbleView = null;
            throw exception;
        }
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
                            requestCapture();
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

    private void showPanel(String source, String translated) {
        if (cleaningUp) {
            return;
        }

        mainHandler.post(() -> {
            if (cleaningUp) {
                return;
            }

            if (panelView == null) {
                createPanelView();
            }

            sourceTextView.setText(source);
            translatedTextView.setText(translated);
            currentTranslation = translated == null ? "" : translated;

            if (!panelAdded) {
                try {
                    windowManager.addView(panelView, panelParams);
                    panelAdded = true;
                } catch (Exception exception) {
                    showToast("Không hiển thị được bảng dịch.");
                }
            }
        });
    }

    private void createPanelView() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(14), dp(18), dp(16));
        card.setElevation(dp(12));

        GradientDrawable cardBackground = new GradientDrawable();
        cardBackground.setColor(Color.parseColor("#FAFFFFFF"));
        cardBackground.setCornerRadius(dp(20));
        cardBackground.setStroke(dp(1), Color.parseColor("#DDD7EE"));
        card.setBackground(cardBackground);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Bản dịch Trung → Việt");
        title.setTextColor(Color.parseColor("#211D31"));
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));

        TextView copyButton = makePanelAction("Sao chép");
        copyButton.setOnClickListener(view -> copyTranslation());
        header.addView(copyButton);

        TextView closeButton = makePanelAction("Đóng");
        closeButton.setOnClickListener(view -> hidePanel());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        closeParams.setMarginStart(dp(8));
        header.addView(closeButton, closeParams);

        card.addView(header);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.min(dp(330), Math.max(dp(210), screenHeight - dp(230)))
        );
        scrollParams.topMargin = dp(10);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView sourceLabel = makeLabel("TIẾNG TRUNG");
        content.addView(sourceLabel);

        sourceTextView = makeBodyText();
        LinearLayout.LayoutParams sourceParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        sourceParams.topMargin = dp(5);
        content.addView(sourceTextView, sourceParams);

        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#E7E2F0"));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
        );
        dividerParams.topMargin = dp(14);
        dividerParams.bottomMargin = dp(14);
        content.addView(divider, dividerParams);

        TextView translatedLabel = makeLabel("TIẾNG VIỆT");
        content.addView(translatedLabel);

        translatedTextView = makeBodyText();
        translatedTextView.setTextColor(Color.parseColor("#392A8E"));
        translatedTextView.setTextSize(17);
        LinearLayout.LayoutParams translatedParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        translatedParams.topMargin = dp(5);
        content.addView(translatedTextView, translatedParams);

        scrollView.addView(content);
        card.addView(scrollView, scrollParams);

        panelView = card;
        panelParams = new WindowManager.LayoutParams(
                Math.max(dp(280), screenWidth - dp(24)),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        panelParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        panelParams.y = dp(58);
    }

    private TextView makePanelAction(String text) {
        TextView action = new TextView(this);
        action.setText(text);
        action.setTextColor(Color.parseColor("#6847F5"));
        action.setTextSize(14);
        action.setTypeface(Typeface.DEFAULT_BOLD);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(9), dp(7), dp(9), dp(7));

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.parseColor("#EEEAFE"));
        background.setCornerRadius(dp(10));
        action.setBackground(background);
        return action;
    }

    private TextView makeLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.parseColor("#716A80"));
        label.setTextSize(12);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        return label;
    }

    private TextView makeBodyText() {
        TextView textView = new TextView(this);
        textView.setTextColor(Color.parseColor("#211D31"));
        textView.setTextSize(15);
        textView.setLineSpacing(0, 1.12f);
        return textView;
    }

    private void copyTranslation() {
        if (currentTranslation.trim().isEmpty()
                || currentTranslation.startsWith("Đang ")) {
            showToast("Chưa có bản dịch để sao chép.");
            return;
        }

        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Bản dịch", currentTranslation));
            showToast("Đã sao chép bản dịch.");
        }
    }

    private void hidePanel() {
        if (panelView != null && panelAdded) {
            try {
                windowManager.removeView(panelView);
            } catch (Exception ignored) {
                // View đã được gỡ.
            }
            panelAdded = false;
        }
    }

    private void hideBubble() {
        if (bubbleView != null) {
            bubbleView.setVisibility(View.INVISIBLE);
        }
    }

    private void showBubble() {
        if (bubbleView != null) {
            bubbleView.setVisibility(View.VISIBLE);
        }
    }

    private void showFailure(String message) {
        busy = false;
        captureNextFrame = false;
        showBubble();
        showPanel("", message);
    }

    private void showFailureWithSource(String source, String message) {
        busy = false;
        captureNextFrame = false;
        showBubble();
        showPanel(source, message);
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

        captureNextFrame = false;
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

            if (panelView != null) {
                panelParams.width = Math.max(dp(280), screenWidth - dp(24));
                if (panelAdded) {
                    windowManager.updateViewLayout(panelView, panelParams);
                }
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
                .setContentText("Chạm bong bóng 译 để dịch chữ trên màn hình")
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "Tắt",
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

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_RUNNING, false)
                .apply();

        hidePanel();

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
}
