# dammyy.cc — sample notes (collected 2026-11-13)

**Site type:** Vietnamese **text novel** site (đam mỹ / ngôn tình), Laravel backend + jQuery/Bootstrap theme.
`<html lang="vi">`, `charset=utf-8` (header `text/html; charset=utf-8`). Chapter bodies use HTML entities (`&#7897;` etc.); decode them.
**Anti-bot:** none seen. Plain `curl.exe` with a desktop Chrome UA returned 200 on everything; Cloudflare is in front (`/cdn-cgi/` email-decode script) but there was no challenge. No login is needed to read. JS has a `/ajax/buy-chapter` (paid chapters), but no locked chapter showed up in these samples.
**Dead books:** several books in the "Truyện dài" ranking sidebar (e.g. `/co-xuyen-kim-bi-anh-em-tot-lua-len-giuong-trong-gioi-giai-tri.html`) return **HTTP 404** (title "404 Trang bạn truy cập không tồn tại"), and search/autocomplete can't find them either. They are deleted or hidden, so the plugin should handle 404 gracefully.

## Files

| file | URL | status |
|---|---|---|
| home.html | https://dammyy.cc/ | 200 |
| genre1.html | https://dammyy.cc/the-loai/abo.html | 200 |
| genre1-p2.html | https://dammyy.cc/the-loai/abo.html?page=2 | 200 |
| list-moi.html | https://dammyy.cc/truyen-moi.html | 200 (246 pages) |
| list-moi-p2.html | https://dammyy.cc/truyen-moi.html?page=2 | 200 |
| list-hot.html | https://dammyy.cc/truyen-hot.html | 200 |
| list-hoan-thanh.html | https://dammyy.cc/truyen-hoan-thanh.html | 200 |
| list-sang-tac.html | https://dammyy.cc/truyen-sang-tac.html | 200 |
| list-dai.html | https://dammyy.cc/truyen-dai.html | 200 — **no main list** (0 `.story-item-title`), only the sidebar ranking; don't use it |
| search.html | https://dammyy.cc/tim-kiem?search=trong+sinh | 200 (3 pages) |
| search-p2.html | https://dammyy.cc/tim-kiem?search=trong%20sinh&page=2 | 200 |
| ajax-autocomplete.json | https://dammyy.cc/ajax/autocomplete?search=trong%20sinh | 200 JSON |
| detail.html | https://dammyy.cc/giam-cam-ten-bao-quan-phan-dien.html | 200 (26 chapters, completed) |
| detail-long.html | https://dammyy.cc/omega-vua-thom-vua-mem-kia.html | 200 (31 chapters; longest live book found) |
| chap1.html | https://dammyy.cc/giam-cam-ten-bao-quan-phan-dien/chuong-1.html | 200 |
| chap2.html | https://dammyy.cc/giam-cam-ten-bao-quan-phan-dien/chuong-15.html | 200 |
| chap-last.html | https://dammyy.cc/omega-vua-thom-vua-mem-kia/chuong-31-ngoai-truyen-le-cuoi-tot-dep-nhat.html | 200 |
| ajax-get-chapter.json | https://dammyy.cc/ajax/get-chapter?story=giam-cam-ten-bao-quan-phan-dien&chapter=chuong-2 | 200 JSON |
| js/app.min.js | https://dammyy.cc/assets/app.min.js?ver=1.1.0 | 200 |
| icon.png | https://dammyy.cc/images/favicon/android-icon-192x192.png | 200, 192×192 PNG |

## URL patterns
- Book: `https://dammyy.cc/{slug}.html`
- Chapter: `https://dammyy.cc/{slug}/{chapter-slug}.html`. The chapter slug is `chuong-N` or `chuong-N-title-slug`. List cards also link `/{slug}/{N}.html` (e.g. `/khi-omega.../7.html`) for the latest chapter.
- Genre: `https://dammyy.cc/the-loai/{genre}.html?page=N`
- Lists: `/truyen-moi.html`, `/truyen-hot.html`, `/truyen-hoan-thanh.html`, `/truyen-sang-tac.html`, each with `?page=N`
- Search: `GET https://dammyy.cc/tim-kiem?search={kw}&page=N`. The param is **`search`**; `keyword=` is ignored and returns all books.

## Book list items (genre / lists / search; 24 per page)
- Item: `div.col-lg-6.col-12 > div.card`
- Link: `a[href]` wrapping `h3.story-item-title` (or the first `a` in the card)
- Title: `h3.card-title.story-item-title` (text)
- Cover: `img.card-img-top.lazyload` → **`data-src`** (`src` is the ajax-loading.gif placeholder). Thumbs: `https://dammyy.cc/images/story/thumbs/230/YYYY/MM/DD/xxx.jpg`
- Description: `div.story-item-excerpt` (text, contains `&nbsp;`)
- "Author" in a card: `div.author a` = the **uploader/team** (`/user/{id}`), not the original author
- Completed badge: `span.is-full` ("HOÀN")
- Latest chapter: `span.chapter a`; update time: `span.post-on`
- Ignore the sidebar ranking blocks `div.top-item` (90 per page; title is in `.detail h3 a`, cover is `img.lazyload[data-src]`, plus `.last_chapter`)

## Paging
`ul.pagination li.page-item a.page-link`. The next link is the `a.page-link` holding `i.bx-chevrons-right`, and on the last page that li is `disabled` with `href="javascript:;"`. The last page number is the largest numeric `page=` value. Search links use `&amp;page=N`.

## Genre list
In home.html (and every page's sidebar), 63 genres: `h4.item-category > a[href*="/the-loai/"]`. The text comes after `<i class="bx bx-caret-right">`, so trim it. The same set also appears as `a.dropdown-item` in the nav menu.

## Search: JSON alternative
`GET /ajax/autocomplete?search={kw}` → `{"success":true,"data":[...]}` (max 20 results). With no match it returns `{"success":false,"message":"No data."}`. The JS only calls it when the keyword is longer than 3 characters. Fields: `id, name, slug, author` (**real author**), `another_name, categories` (JSON string of genre ids), `thumbnail` (relative, e.g. `2026/11/02/xxx.jpg` → `https://dammyy.cc/images/story/` + thumbnail; thumbs are under `/images/story/thumbs/230/`), `content` (description, HTML-escaped), `last_chapter, total_chapter, is_full, status, type, total_view, updated_at, warning`.

## Detail page
- Title: `h2.card-title[itemprop=name]` (also the breadcrumb `h1 span[itemprop=name]`)
- Cover: `div.col-md-3 img.img-fluid` → `src` (full size `https://dammyy.cc/images/story/YYYY/MM/DD/xxx.jpg`)
- Info: `dl.row` with `dt`/`dd` pairs: "Cập nhật", "Loại" (Truyện Chữ), "Thể loại" (`dd a.cate-item[itemprop=genre]`, href `/the-loai/x.html`), "Team" (`dd a b` = translator/uploader), "Lượt xem", "Trạng thái" (e.g. "Đã đủ bộ" = completed). **There is no author field in the detail HTML**; use the autocomplete JSON `author` if you need it.
- Description: `div.story-description div.ql-editor[itemprop=description]` → **obfuscated** (see below)
- Story id: `onclick="bookmark(8414, 'slug')"` / `report(8414)`; also `input#story_id` on chapter pages
- First/latest chapter buttons: `#control a.btn-warning` / `#control a.btn-success`

## TOC (chapter list)
All chapters are **in the detail HTML, with no paging** (verified: 26/26 and 31/31). The largest live book I found had only 31 chapters, so paging for 100+ chapters could not be tested.
- `div#listChapters div.list-chapters div.item div.episode-title a` → href + text ("Chương 1."); date is in `div.episode-date span`
- Order: **newest first**, so reverse it
- Fallback: on chapter pages `select#selected_chapter option[value="{story-slug},{chapter-slug}"]` lists every chapter. There are 2 selects per page, so there are duplicates.

## Chapter page
- The content is **in the HTML** (no JS needed): `div#chapter-content-render` (inside `div.chapter-content` inside `div#chapterContentLoading`)
- Title: `h1.card-title` ("Book - Chương N.") or `input#chapter_title[value]`; ids are in `input#chapter_id`, `input#story_id`
- Prev/next: `a#prev_chapter_btn` / `a#next_chapter_btn`, `onclick="actionChangeChapter('story-slug,chuong-14')"` → URL `/{story-slug}/{chuong-14}.html`. If there is no onclick, there is no such chapter.
- AJAX alternative: `GET /ajax/get-chapter?story={slug}&chapter={chapter-slug}` → JSON `{success, html, next_chapter, prev_chapter, chapterName, stringSlug, url, storySlug, viewToken, viewDelay, csrfToken}`. `html` has the same markup **including the `<style>` word map**.

### Obfuscation (detail description AND chapter text)
Some words are replaced by empty spans: `<span class="v-38cc84a0083fff9b160e43f2e2457b13"></span>`. Their text comes from inline `<style>` CSS on the same page: `.v-38cc84a0083fff9b160e43f2e2457b13:before { content: "ta"; }`.
- Regex for the map: `\.([a-z]-[0-9a-f]{32}):before\s*\{\s*content:\s*"([^"]*)"`
- Regex for a span: `<span class="([a-z]-[0-9a-f]{32})"></span>` → replace with `map[cls]`
- The map holds 75 common words (tôi, ta, huynh, muội, chàng, nói, ra, vào, …). It was **identical** across home, detail, chap1, chap2 and the AJAX response, and every used class was found (0 missing). Still, build it from each page's own `<style>`, not from a hard-coded table.

### Junk to strip from #chapter-content-render
- A stray `<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.0 Transitional//EN" ...>` at the start of the content
- `p.signature` paragraphs inserted mid-text: the watermark "[Truyện được đăng tải duy nhất tại dammy.me - <a>chapter url</a>.]" and the team name (e.g. "Hồng Trà Kem Cheese"). Remove every `p.signature`.
- Bare section-number paragraphs like `<p>1</p>` are original story content; keep them.
- Censored words written with dots, e.g. "g.i.ế.t", "c.h.ế.t", "m.á.u", "uy h.i.ế.p". You could optionally remove the dots with `([\p{L}])\.(?=[\p{L}]\.|[\p{L}]\b)`. In excerpts it is written as `gh/ét`.
- Everything outside the container (comments, report button, ads/affiliate Shopee/Lazada links) is already excluded when you select `#chapter-content-render`.
