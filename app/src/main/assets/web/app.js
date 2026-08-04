/* Photo Server 照片墙前端
 * - 缩略图照片墙（懒加载）
 * - 点击查看大图 + EXIF + 原图下载
 * - 定时查询 /api/status（轻量 DB 计数），发现新照片自动刷新
 */
(function () {
  'use strict';

  const PAGE_SIZE = 100;
  const REFRESH_MS = 5000;

  let photos = [];        // 当前已加载的照片（新 -> 旧）
  let page = 0;
  let reachedEnd = false;
  let loading = false;
  let latestId = 0;
  let lbIndex = -1;

  const grid = document.getElementById('grid');
  const emptyEl = document.getElementById('empty');
  const countEl = document.getElementById('photo-count');
  const lb = document.getElementById('lightbox');
  const lbImg = document.getElementById('lb-img');
  const lbName = document.getElementById('lb-name');
  const lbExif = document.getElementById('lb-exif');
  const lbDownload = document.getElementById('lb-download');

  // ---------- 数据加载 ----------

  async function fetchJson(url) {
    const r = await fetch(url);
    if (!r.ok) throw new Error(url + ' -> ' + r.status);
    return r.json();
  }

  async function loadPage() {
    if (loading || reachedEnd) return;
    loading = true;
    try {
      const list = await fetchJson('/api/photos?page=' + page + '&size=' + PAGE_SIZE);
      if (list.length < PAGE_SIZE) reachedEnd = true;
      list.forEach(function (p) {
        if (!photos.some(function (x) { return x.id === p.id; })) {
          photos.push(p);
          grid.appendChild(makeCell(p, photos.length - 1));
        }
        if (p.id > latestId) latestId = p.id;
      });
      page++;
      updateEmpty();
    } catch (e) { console.error(e); }
    loading = false;
  }

  async function checkNew() {
    try {
      const st = await fetchJson('/api/status');
      countEl.textContent = st.photos + ' 张照片';
      if (st.latestId > latestId) {
        // 有新照片：取第一页，把新出现的插到最前
        const list = await fetchJson('/api/photos?page=0&size=' + PAGE_SIZE);
        const fresh = list.filter(function (p) { return p.id > latestId; });
        fresh.reverse().forEach(function (p) {
          photos.unshift(p);
          grid.insertBefore(makeCell(p, 0), grid.firstChild);
          if (p.id > latestId) latestId = p.id;
        });
        refreshIndices();
        updateEmpty();
      }
    } catch (e) { /* 服务暂不可达，下轮再试 */ }
  }

  function updateEmpty() {
    emptyEl.classList.toggle('hidden', photos.length > 0);
  }

  // ---------- DOM ----------

  const io = new IntersectionObserver(function (entries) {
    entries.forEach(function (en) {
      if (en.isIntersecting) {
        const img = en.target.querySelector('img[data-src]');
        if (img) {
          img.src = img.dataset.src;
          img.removeAttribute('data-src');
        }
        io.unobserve(en.target);
      }
    });
  }, { rootMargin: '300px' });

  function makeCell(p, index) {
    const cell = document.createElement('div');
    cell.className = 'cell';
    cell.dataset.index = index;

    const ph = document.createElement('div');
    ph.className = 'placeholder';
    ph.textContent = '🖼';
    cell.appendChild(ph);

    const img = document.createElement('img');
    img.dataset.src = p.thumbnail;
    img.alt = p.name;
    img.onload = function () { img.classList.add('loaded'); ph.remove(); };
    img.onerror = function () { ph.textContent = '⏳'; };
    cell.appendChild(img);

    const name = document.createElement('div');
    name.className = 'name';
    name.textContent = p.name;
    cell.appendChild(name);

    cell.addEventListener('click', function () {
      openLightbox(parseInt(cell.dataset.index, 10));
    });
    io.observe(cell);
    return cell;
  }

  function refreshIndices() {
    Array.prototype.forEach.call(grid.children, function (cell, i) {
      cell.dataset.index = i;
    });
  }

  // ---------- Lightbox ----------

  function openLightbox(i) {
    if (i < 0 || i >= photos.length) return;
    lbIndex = i;
    const p = photos[i];
    lbImg.src = p.original;
    lbName.textContent = p.name;
    lbDownload.href = p.original;
    lbDownload.setAttribute('download', p.name);

    const ex = p.exif || {};
    const parts = [];
    if (ex.focalLength) parts.push('📏 ' + ex.focalLength);
    if (ex.aperture) parts.push('🔘 ' + ex.aperture);
    if (ex.shutter) parts.push('⚡ ' + ex.shutter);
    if (ex.iso) parts.push('ISO ' + ex.iso);
    if (ex.dateTime) parts.push('🕒 ' + ex.dateTime);
    if (ex.model) parts.push('📷 ' + ex.model);
    if (p.width && p.height) parts.push(p.width + '×' + p.height);
    if (p.size) parts.push(fmtSize(p.size));
    lbExif.innerHTML = parts.map(function (t) {
      return '<span>' + escapeHtml(t) + '</span>';
    }).join('');

    lb.classList.remove('hidden');
    document.body.style.overflow = 'hidden';
  }

  function closeLightbox() {
    lb.classList.add('hidden');
    lbImg.src = '';
    document.body.style.overflow = '';
    lbIndex = -1;
  }

  function nav(delta) {
    const next = lbIndex + delta;
    if (next >= 0 && next < photos.length) openLightbox(next);
  }

  document.getElementById('lb-close').addEventListener('click', closeLightbox);
  document.getElementById('lb-prev').addEventListener('click', function () { nav(1); });
  document.getElementById('lb-next').addEventListener('click', function () { nav(-1); });
  lb.addEventListener('click', function (e) { if (e.target === lb || e.target.classList.contains('lb-stage')) closeLightbox(); });
  document.addEventListener('keydown', function (e) {
    if (lb.classList.contains('hidden')) return;
    if (e.key === 'Escape') closeLightbox();
    if (e.key === 'ArrowLeft') nav(1);
    if (e.key === 'ArrowRight') nav(-1);
  });

  // ---------- 工具 ----------

  function fmtSize(bytes) {
    if (bytes > 1048576) return (bytes / 1048576).toFixed(1) + ' MB';
    if (bytes > 1024) return (bytes / 1024).toFixed(0) + ' KB';
    return bytes + ' B';
  }

  function escapeHtml(s) {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  // 无限滚动
  new IntersectionObserver(function (entries) {
    if (entries[0].isIntersecting) loadPage();
  }, { rootMargin: '600px' }).observe(document.getElementById('load-more-sentinel'));

  // 启动
  loadPage().then(checkNew);
  setInterval(checkNew, REFRESH_MS);
})();
