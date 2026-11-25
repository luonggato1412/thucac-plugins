# Quy ước viết plugin Thư Các

## Thông tin bắt buộc
- `author`: `luonggato` cho plugin tự viết (ở cả `plugin.json` trong zip và `plugin.json` gốc của kho).
- Điền đủ `locale` (vi_VN, en_US, zh_CN, zh_TW, ja_JP, global), `type` (novel, chinese_novel, comic) và tag `nsfw` nếu 18+.
- Sửa plugin thì tăng `version` ở cả hai `plugin.json`. Plugin có sẵn trong app (`app/src/main/assets/*.zip` của thucac-app) thì cập nhật luôn bản zip đó.

## Cấu trúc
- `plugin.json` (`metadata`, `script`), `icon.png`, `src/*.js` theo vai trò: `home`, `genre`, `gen`, `search`, `detail`, `page`, `toc`, `chap`.
- Hàm dùng chung để trong `src/config.js`, các script khác `load("config.js")`. App đã cache script biên dịch sẵn (từ 0.6.37), nên load nhiều lần không tốn parse lại.

## Hiệu năng (app 0.6.38 trở lên)
1. **Không nhúng bảng mã JS** (như `gbk.js` 68 KB). Mã hoá từ khoá theo bảng mã khác UTF-8 bằng hàm có sẵn:
   `encodeURIComponentWithCharset(key, "GBK")` (dùng được với GBK, GB2312, Big5, Shift_JIS...).
2. **Dùng helper mặc định thay vì tự viết lại** `fetchRaw`, `fetchDoc`, `fetchText`:
   - URL tương đối được nối với `metadata.source`.
   - Tự thêm `Referer: <source>/` nếu chưa có.
   - Trang không phải UTF-8 thì khai báo `"charset": "gbk"` trong `metadata`, `fetchDoc`/`fetchText` sẽ tự giải mã.
   - Biến `PLUGIN_SOURCE`, `PLUGIN_CHARSET` có sẵn. Nếu `config.js` tự định nghĩa hàm trùng tên thì hàm của plugin được dùng.
3. **`chap.js` trả thẳng mảng đoạn văn** (`Response.success(lines)`) hoặc chuỗi nối bằng `\n`. Đừng `lines.join("<br>")` vì app phải parse HTML lại để tách đoạn. Trả HTML vẫn chạy được (cho nội dung lấy nguyên khối từ trang).
4. **Mục lục nhiều trang**: `page.js` trả danh sách trang, app chạy `toc.js` 3 trang cùng lúc và giữ đúng thứ tự. Không cần tự gộp trang trong `toc.js`.

## Tương thích
- Plugin dùng các hàm ở mục Hiệu năng cần app 0.6.38 trở lên. Bản app cũ sẽ báo lỗi khi tìm kiếm hoặc tải trang.
