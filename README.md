# Kho plugin Thư Các

Kho tổng hợp plugincho app Thư Các: plugin riêng của Thư Các + plugin từ các kho cộng đồng trong "Quản lý nguồn", đã loại trùng (cùng tên + cùng trang thì giữ bản version cao nhất).

Thêm vào app: Cài đặt → Nguồn truyện → + → dán link raw của `plugin.json` trong kho này.

- `plugin.json`: danh sách plugin (đường dẫn tương đối `plugins/…zip`, `icons/…png`)
- `plugins/`: file zip của từng plugin
- `tools/repos.txt`, `tools/merge.py`, `tools/normalize.py`: danh sách kho nguồn, script gộp, script chuẩn hoá type/locale (chạy merge rồi normalize)
