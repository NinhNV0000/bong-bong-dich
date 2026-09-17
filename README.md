# Bong Bóng Dịch

Ứng dụng Android nhận chữ Trung trong game và phủ bản dịch tiếng Việt ngay tại vị trí tương ứng.

## Phiên bản 1.10.2

- Chỉ giữ một bong bóng × để đóng bản dịch; bỏ nút đóng dự phòng bị nhân đôi.
- Dịch tối đa 8 vùng song song, gộp các vùng có cùng chữ và hiển thị từng ô ngay khi xong.
- Tái sử dụng kết nối Google Translate khi phản hồi đã được đọc đủ để giảm thời gian chờ giữa các yêu cầu.
- Nối các dòng OCR trong cùng một khối thành câu hoàn chỉnh trước khi gửi dịch; dấu câu thật vẫn được giữ nguyên.
- Chuyển sang dịch riêng từng vùng OCR theo nhiều luồng; không còn ghép hàng loạt bằng mã đánh dấu dễ gây lệch nội dung giữa các ô.
- Khóa từ điển người dùng và thuật ngữ game bằng mã tạm, sau đó phục hồi cách dịch mong muốn vào câu tiếng Việt.
- Kiểm tra tự động kết quả đáng ngờ: còn nhiều chữ Trung, làm rơi số, lộ mã tạm hoặc độ dài bất thường.
- Tự thử lại bằng câu gốc nếu bản có khóa thuật ngữ gặp lỗi.
- Bổ sung cách dịch cố định cho thuật ngữ game và tên nhân vật Naruto thường gặp.
- Giữ nguyên giao diện ảnh dịch và cơ chế chặn Back; nút × dự phòng bị trùng đã được bỏ.
- Vẫn ưu tiên chất lượng dịch, nhưng các ô hoàn tất sẽ xuất hiện ngay thay vì chờ cả màn hình.
- Tiếp tục lưu từ điển tại `BongBongDich/tu-dien.json`.
- Bản vẫn dùng chữ ký build tạm như các bản trước.

## Nền tảng ổn định từ v1.9.1

- Khôi phục nguyên bộ dịch ổn định của v1.8.0 sau khi thử nghiệm v1.9.0 cho kết quả kém hơn.
- Gỡ cơ chế tự chèn quá nhiều thuật ngữ vào câu Trung và tách riêng câu dài.
- Giao diện sáng xanh, gọn hơn và icon mới theo thiết kế của người dùng.
- Thêm Từ điển chuyên ngành để tự dạy chữ Trung → cách dịch tiếng Việt.
- Có thể thêm, sửa, xóa riêng từng thuật ngữ hoặc xóa toàn bộ; dữ liệu được lưu trên máy.
- Thuật ngữ người dùng dạy luôn được ưu tiên trước Google và từ điển mặc định.
- Thêm chế độ ảnh dịch toàn màn hình: giữ ảnh game, che chữ Trung và đặt chữ Việt tại chỗ.
- Tự lấy màu nền xung quanh vùng chữ và chọn chữ sáng/tối để dễ đọc hơn.
- Khóa thao tác xuống game trong lúc ảnh dịch đang mở; chạm × để quay lại game.
- Gom nhiều ô chữ vào một lượt dịch online để giảm thời gian chờ.
- Thu gọn ảnh trước OCR và lưu bản dịch đã gặp để lần sau phản hồi nhanh hơn.
- Có từ điển thuật ngữ game Trung–Việt cho lực chiến, phó bản, đội hình, chiêu mộ, tăng sao, quét và nhiều từ khác.
- Không tải hoặc chuyển sang model dịch offline.
- Kéo bong bóng xuống vùng × để tắt hẳn.
- Khung tiếng Việt được nới nhẹ khi có khoảng trống nhưng không che vùng chữ khác.
- Đồng bộ ảnh chụp và lớp phủ sau khi xoay màn hình.
- Chạm **译** để dịch; chạm **×** để ẩn.

## Quyền riêng tư

- Ảnh màn hình chỉ được OCR trong điện thoại và không lưu thành tệp.
- Chỉ chuỗi chữ Trung đã nhận dạng được gửi tới dịch vụ dịch online.
- Ứng dụng không có tài khoản và không có máy chủ riêng.

## Yêu cầu

- Android 8.0 trở lên, kiến trúc ARM64.
- Cần có mạng mỗi lần dịch.
- Game không chặn chụp màn hình bằng chế độ bảo mật.
