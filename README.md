# Bong Bóng Dịch

Ứng dụng Android dịch chữ Trung trong game sang tiếng Việt ngay trên màn hình.

## Cách hoạt động

1. Mở ứng dụng và bấm **Bật bong bóng dịch**.
2. Cấp quyền **hiển thị trên ứng dụng khác**.
3. Đồng ý **chia sẻ toàn bộ màn hình**.
4. Mở game Trung Quốc và chạm bong bóng **译**.
5. Ứng dụng nhận dạng từng vùng chữ Trung rồi phủ bản dịch tiếng Việt ngay đúng vị trí đó.
6. Chạm lại **译** khi màn hình game thay đổi để cập nhật bản dịch.

Lớp chữ dịch không chặn thao tác chạm vào game. Ảnh chụp chỉ được xử lý trong điện thoại, không lưu thành tệp và không tải lên máy chủ riêng.

## Tải APK

Mở mục [Releases](https://github.com/NinhNV0000/bong-bong-dich/releases/latest) và tải tệp `BongBongDich-v1.1.0-arm64.apk`.

## Yêu cầu

- Android 8.0 trở lên, kiến trúc ARM64.
- Cho phép ứng dụng hiển thị trên ứng dụng khác.
- Chọn chia sẻ **Toàn bộ màn hình** để tọa độ bản dịch khớp với game.
- Game không chặn chụp màn hình bằng chế độ bảo mật.

## Quyền riêng tư

- OCR tiếng Trung dùng mô hình ML Kit được đóng gói trong APK.
- Mô hình dịch Trung–Việt chạy trên thiết bị sau khi tải về.
- Ứng dụng không lưu ảnh màn hình, không có tài khoản và không có máy chủ riêng.
