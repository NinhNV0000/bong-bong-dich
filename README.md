# Bong Bóng Dịch

Ứng dụng Android hỗ trợ dịch nhanh chữ Trung trên màn hình sang tiếng Việt.

## Cách hoạt động

1. Mở ứng dụng và bấm **Bật bong bóng dịch**.
2. Cấp quyền **hiển thị trên ứng dụng khác**.
3. Đồng ý **chia sẻ toàn bộ màn hình**.
4. Mở game Trung Quốc, sau đó chạm bong bóng **译** để chụp màn hình, nhận dạng chữ và dịch.
5. Kéo bong bóng đến vị trí thuận tiện. Kết quả hiện trong một khung nổi; bấm **Sao chép** hoặc **Đóng**.

Ảnh chụp và nội dung dịch được xử lý trên thiết bị, không lưu thành tệp và không tải lên máy chủ riêng. Lần dịch đầu tiên cần Internet để tải mô hình dịch Trung–Việt (khoảng 30 MB).

## Tải APK

Mở mục [Releases](https://github.com/NinhNV0000/bong-bong-dich/releases/latest) và tải tệp `BongBongDich-v1.0.1-arm64.apk`.

Bản v1.0.1 đã được tối ưu cho điện thoại Android 64-bit để giảm đáng kể dung lượng tải xuống.

## Yêu cầu

- Android 8.0 trở lên, kiến trúc ARM64.
- Cho phép ứng dụng hiển thị trên ứng dụng khác.
- Đồng ý chia sẻ màn hình mỗi khi bật bong bóng.
- Game không chặn chụp màn hình bằng chế độ bảo mật.

## Quyền riêng tư

- OCR tiếng Trung dùng mô hình ML Kit được đóng gói trong APK.
- Mô hình dịch Trung–Việt chạy trên thiết bị sau khi tải về.
- Ứng dụng không lưu ảnh màn hình, không có tài khoản và không có máy chủ riêng.
