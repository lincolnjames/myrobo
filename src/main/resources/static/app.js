// 한 페이지 홈. 서버 API: GET /api/portfolio, PUT /api/portfolio/snapshot, GET /api/valuation, GET·POST /api/plans
// 변경 요청에는 X-Robo-Request 헤더가 필요하다 (CSRF 방어, LocalRequestGuard 참고).
// 색: 매수·상승 빨강, 매도·하락 파랑 (국내 증권 앱 관례). 종목 색은 index.html의 --s-* (팔레트 검증 통과한 값)
(function () {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const krw = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 0 });
  const usd = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

  const fmtKrw = (v) => (v == null ? '–' : krw.format(Number(v)) + '원');
  const fmtUsd = (v) => (v == null ? '–' : '$' + usd.format(Number(v)));
  const fmtPct = (v, signed) => {
    if (v == null) return '–';
    const n = Number(v);
    return (signed && n > 0 ? '+' : '') + n.toFixed(2) + '%';
  };
  const signClass = (v) => (v == null || Number(v) === 0 ? '' : Number(v) > 0 ? 'up' : 'down');
  const todayKst = () => new Date(Date.now() + 9 * 3600 * 1000).toISOString().slice(0, 10);
  const daysBetween = (a, b) => Math.round((Date.parse(b) - Date.parse(a)) / 86400000);
  const md = (d) => (d ? d.slice(5) : '');

  /** 화면 표시용 종목 정보. 규칙(robo-config.yml)이 아니라 설명일 뿐이라 여기에 둔다. */
  const ETF = {
    VTI: { name: '미국 전체 주식', full: 'Vanguard Total Stock Market ETF', role: '성장', color: 'var(--s-vti)',
      about: '미국에 상장된 주식 약 3,500개를 회사 크기 비중대로 한 번에 담아요. 오래 두고 키우는 몫이에요.' },
    SCHR: { name: '미국 중기 국채', full: 'Schwab Intermediate-Term U.S. Treasury ETF', role: '안정', color: 'var(--s-schr)',
      about: '만기 3~10년 미국 국채를 담아요. 주식이 크게 떨어질 때 덜 흔들리게 받쳐 주는 몫이에요.' },
    GLDM: { name: '금', full: 'SPDR Gold MiniShares Trust', role: '위기 대비', color: 'var(--s-gldm)',
      about: '금고에 보관한 실물 금의 가격을 따라가요. 주식·채권과 다르게 움직이길 기대하는 몫이에요.' },
    BIL: { name: '미국 초단기 국채', full: 'SPDR Bloomberg 1-3 Month T-Bill ETF', role: '현금성', color: 'var(--s-bil)',
      about: '만기 1~3개월 미국 국채라 값이 거의 움직이지 않아요. 달러 현금 대신 이자를 받으며 들고 있는 자리예요.' },
  };
  const SERIES_ORDER = ['VTI', 'SCHR', 'GLDM', 'BIL'];
  const info = (s) => ETF[s] || { name: '', full: s, role: '', color: 'var(--s-other)', about: '시스템이 관리하지 않는 종목이에요.' };

  /** 세 API의 마지막 응답. 버튼 상태처럼 여러 응답을 함께 봐야 하는 판단에 쓴다. */
  const state = { portfolio: null, plans: null, valuation: null };

  function el(tag, attrs, ...children) {
    const e = document.createElement(tag);
    Object.entries(attrs || {}).forEach(([k, v]) => {
      if (v == null) return;
      if (k === 'class') e.className = v; else e.setAttribute(k, v);
    });
    children.flat(Infinity).forEach((c) => e.append(c instanceof Node ? c : document.createTextNode(c == null ? '' : String(c))));
    return e;
  }

  function svg(tag, attrs) {
    const e = document.createElementNS('http://www.w3.org/2000/svg', tag);
    Object.entries(attrs || {}).forEach(([k, v]) => e.setAttribute(k, v));
    return e;
  }

  function avatar(symbol) {
    if (symbol === '현금') return el('div', { class: 'avatar cash', style: 'background:var(--s-cash)', 'aria-hidden': 'true' }, '₩$');
    return el('div', { class: 'avatar' + (symbol.length > 3 ? ' long' : ''), style: 'background:' + info(symbol).color, 'aria-hidden': 'true' }, symbol);
  }

  // ── 툴팁 ──

  function showTip(lines, x, y) {
    const t = $('tip');
    t.replaceChildren(...lines.map((l, i) => (i === 0 ? el('b', {}, l) : el('div', {}, l))));
    t.style.left = x + 'px';
    t.style.top = y + 'px';
    t.style.display = 'block';
  }
  const hideTip = () => { $('tip').style.display = 'none'; };

  // ── 총자산 ──

  function notice(kind, text) {
    $('notices').append(el('div', { class: 'notice ' + kind }, text));
  }

  function renderHero() {
    const data = state.portfolio;
    if (!data) return;
    const v = data.valuation;
    $('total').textContent = fmtKrw(v.totalKrw);
    $('totalSub').textContent = [
      fmtUsd(v.totalUsd),
      data.fx ? '환율 ' + usd.format(Number(data.fx.rate)) + '원 (' + md(data.fx.date) + ')' : '환율 없음',
    ].join(' · ');

    const chips = [];
    const last = state.valuation && (state.valuation.recent || [])[0];
    if (last) {
      chips.push(chip('직전 기록 대비', fmtPct(last.returnPct, true), signClass(last.returnPct)));
      const sinceStart = Number(last.nav) - 100;
      chips.push(chip('시작 이후', fmtPct(sinceStart, true), signClass(sinceStart)));
      chips.push(chip('고점 대비', fmtDd(last.drawdownPct), ''));
    }
    chips.push(chip('현금', fmtUsd(v.cashTotalUsd), ''));
    $('chips').replaceChildren(...chips);

    $('notices').replaceChildren();
    if (!data.asOf) notice('warn', '아직 입력된 잔고가 없어요. 아래 "잔고 입력"에서 오늘 잔고를 입력하세요.');
    else if (data.snapshotAgeDays > 7) notice('bad', '잔고가 ' + data.snapshotAgeDays + '일 전 기록이에요. 7일이 지나면 주문안이 만들어지지 않아요.');
    if (v.missing && v.missing.length) notice('warn', '비중을 계산할 수 없어요. 없는 데이터: ' + v.missing.join(', ') + ' (수집을 먼저 돌려 주세요)');
  }

  function chip(label, value, cls) {
    return el('div', { class: 'chip' }, label, el('b', { class: 'num ' + cls }, value));
  }

  function renderMeta() {
    const data = state.portfolio;
    if (!data) return;
    const priceDates = (data.valuation.rows || []).map((r) => r.priceDate).filter(Boolean).sort();
    $('meta').textContent = [
      data.broker === 'SAMSUNG' ? '삼성증권' : data.broker,
      '잔고 ' + (data.asOf ? md(data.asOf) : '없음'),
      '시세 ' + (priceDates.length ? md(priceDates[0]) + ' 종가' : '없음'),
    ].join(' · ');
  }

  // ── 보유 종목 ──

  function renderHoldings() {
    const data = state.portfolio;
    if (!data) return;
    const v = data.valuation;
    const avg = new Map((data.positions || []).map((p) => [p.symbol, p.avgPrice]));
    const rate = data.fx ? Number(data.fx.rate) : null;
    const rows = [...(v.rows || [])].sort((a, b) =>
      Number(b.valueUsd || 0) - Number(a.valueUsd || 0) || Number(b.targetPct || 0) - Number(a.targetPct || 0));

    const items = rows.map((r) => {
      const i = info(r.symbol);
      const a = avg.get(r.symbol);
      const pl = a != null && r.price != null && r.qty > 0 ? (Number(r.price) / Number(a) - 1) * 100 : null;
      const amountSub = pl != null
        ? el('span', { class: 'num ' + signClass(pl) }, fmtPct(pl, true))
        : el('span', { class: 'meta num' }, rate != null && r.valueUsd != null ? '약 ' + fmtKrw(Number(r.valueUsd) * rate) : '');
      return holdingRow({
        symbol: r.symbol, avatar: avatar(r.symbol),
        title: [r.symbol, el('span', { class: 'name' }, i.name)],
        detail: r.qty > 0 ? krw.format(r.qty) + '주 · ' + (r.price == null ? '시세 없음' : fmtUsd(r.price)) : '보유 없음 · ' + (r.price == null ? '시세 없음' : fmtUsd(r.price)),
        amount: fmtUsd(r.valueUsd), amountSub,
        color: i.color, weightPct: r.weightPct, targetPct: r.targetPct, deviationPct: r.deviationPct,
        bandPct: r.bandPct, breach: r.breach,
        about: [
          el('p', {}, el('b', {}, i.full), i.role ? '  · ' + i.role : ''),
          el('p', {}, i.about),
          el('p', { class: 'meta' }, '목표 비중 ' + fmtPct(r.targetPct) + (r.bandPct == null ? '' : ' · 허용 범위 ±' + Number(r.bandPct).toFixed(2) + '%p')
            + (r.priceDate ? ' · 시세 ' + r.priceDate + ' 종가' : '')),
        ],
      });
    });
    items.push(holdingRow({
      symbol: '현금', avatar: avatar('현금'),
      title: ['현금', el('span', { class: 'name' }, '원화 + 달러')],
      detail: data.cash ? '원화 ' + fmtKrw(data.cash.krw) + ' · 달러 ' + fmtUsd(data.cash.usd) : '입력 없음',
      amount: fmtUsd(v.cashTotalUsd), amountSub: el('span', { class: 'meta' }, '달러 환산'),
      color: 'var(--s-cash)', weightPct: v.cashWeightPct, targetPct: v.cashTargetPct, breach: false,
      about: [el('p', {}, '목표의 ' + fmtPct(v.cashTargetPct) + '는 수수료와 환율 변동에 대비해 남겨 두는 현금 버퍼예요.')],
    }));
    $('holdings').replaceChildren(...items);

    $('excluded').textContent = v.excluded && v.excluded.length
      ? '계산에서 제외된 보유 종목(시스템 밖): ' + v.excluded.map((p) => p.symbol + ' ' + p.qty + '주').join(', ')
      : '';
  }

  function holdingRow(h) {
    const w = h.weightPct == null ? null : Number(h.weightPct);
    const t = h.targetPct == null ? null : Number(h.targetPct);
    const bar = el('div', { class: 'bar', role: 'img',
      'aria-label': h.symbol + ' 현재 비중 ' + fmtPct(w) + ', 목표 ' + fmtPct(t) });
    if (w != null) bar.append(el('div', { class: 'fill', style: 'width:' + Math.min(w, 100) + '%;background:' + h.color }));
    if (t != null) bar.append(el('div', { class: 'target', style: 'left:calc(' + Math.min(t, 100) + '% - 1px)' }));
    let status = '';
    if (w != null && t != null && h.deviationPct != null) {
      const d = Number(h.deviationPct);
      status = h.breach
        ? el('span', { class: 'badge warn' }, (d > 0 ? '목표보다 많음 ' : '목표보다 적음 ') + fmtPct(d, true).replace('%', '%p'))
        : el('span', {}, '범위 안 (' + fmtPct(d, true).replace('%', '%p') + ')');
    }
    return el('details', { class: 'holding' },
      el('summary', {},
        el('div', { class: 'row' },
          h.avatar,
          el('div', { class: 'body' }, el('div', { class: 'title' }, h.title), el('div', { class: 'detail num' }, h.detail)),
          el('div', { class: 'amount num' }, el('b', {}, h.amount), h.amountSub)),
        el('div', { class: 'weight' }, bar,
          el('div', { class: 'legend num' }, el('span', {}, '현재 ' + fmtPct(w) + ' · 목표 ' + fmtPct(t)), status))),
      el('div', { class: 'about' }, h.about));
  }

  // ── 자산 배분 ──

  function renderAlloc() {
    const data = state.portfolio;
    if (!data) return;
    const v = data.valuation;
    const bySymbol = new Map((v.rows || []).map((r) => [r.symbol, r]));
    const parts = SERIES_ORDER.filter((s) => bySymbol.has(s)).map((s) => ({
      key: s, label: s + ' ' + info(s).name, color: info(s).color,
      now: Number(bySymbol.get(s).weightPct || 0), target: Number(bySymbol.get(s).targetPct || 0) }));
    parts.push({ key: '현금', label: '현금', color: 'var(--s-cash)', now: Number(v.cashWeightPct || 0), target: Number(v.cashTargetPct || 0) });

    const seg = (field, name) => {
      const box = el('div', { class: 'seg', role: 'img',
        'aria-label': name + ': ' + parts.map((p) => p.label + ' ' + p[field].toFixed(2) + '%').join(', ') });
      parts.filter((p) => p[field] > 0).forEach((p) => {
        const s = el('div', { style: 'flex:' + p[field] + ' 1 0;background:' + p.color });
        s.addEventListener('pointermove', (e) => showTip([p.label, name + ' ' + p[field].toFixed(2) + '%'], e.clientX, e.clientY - 8));
        s.addEventListener('pointerleave', hideTip);
        box.append(s);
      });
      return el('div', { class: 'alloc-row' }, el('span', { class: 'lbl' }, name), box);
    };
    if (v.totalUsd == null) {
      $('alloc').replaceChildren(el('p', { class: 'meta' }, '시세나 잔고가 없어 비중을 그릴 수 없어요.'));
      return;
    }
    $('alloc').replaceChildren(
      seg('now', '현재'), seg('target', '목표'),
      el('div', { class: 'keys' }, parts.map((p) => el('span', { style: '--c:' + p.color }, p.label))));
  }

  // ── 누적 수익률 ──

  const LEVEL = { ALERT: ['warn', '경보'], STOP_SATELLITE_BUYS: ['bad', '위성 매수 중단'], LIMIT: ['bad', '낙폭 한도 도달'] };
  const fmtDd = (v) => (v == null ? '–' : Number(v) === 0 ? '0.00%' : '-' + Number(v).toFixed(2) + '%');

  function levelBadge(level, levels) {
    if (!level) return el('span', { class: 'badge ok' }, '정상');
    const l = levels.find((x) => x.level === level);
    const [kind, text] = l ? (LEVEL[l.action] || ['warn', l.action]) : ['warn', '단계 ' + level];
    return el('span', { class: 'badge ' + kind }, text + (l ? ' · ' + Number(l.atPct) + '%' : ''));
  }

  function renderValuation(o) {
    state.valuation = o;
    const levels = o.levels || [];
    const recent = o.recent || [];
    $('valRule').textContent = '입출금·매매 영향을 뺀 지수(첫 기록 = 100)가 가장 높았던 때보다 얼마나 내려왔는지로 경보를 걸어요. '
      + '경보선 ' + levels.map((l) => Number(l.atPct) + '%').join(' · ') + ' (마지막은 목표의 최대 낙폭 한도). 매일 08:00 작업이 기록해요.';
    $('valNotice').replaceChildren();
    const note = (kind, text) => $('valNotice').append(el('div', { class: 'notice ' + kind }, text));

    const last = recent[0];
    if (!last) {
      $('valMeta').textContent = '';
      $('valChart').replaceChildren();
      $('valHistory').replaceChildren();
      note('warn', '아직 기록이 없어요. 잔고를 입력하면 다음 매일 작업부터 기록돼요.');
      renderHero();
      return;
    }
    $('valMeta').textContent = '입출금 영향 제외 · ' + md(recent[recent.length - 1].date) + ' 시작';
    drawChart([...recent].reverse());

    if (last.level > 0) {
      const l = levels.find((x) => x.level === last.level);
      note(LEVEL[l && l.action] ? LEVEL[l.action][0] : 'warn', '고점(' + last.peakDate + ') 대비 ' + Number(last.drawdownPct).toFixed(2)
        + '% 내려와 있어요. 규칙은 그대로예요. 주문은 발송된 주문안대로만 넣으세요.');
    }
    const age = daysBetween(last.date, todayKst());
    if (age > 1) note('warn', '최근 기록이 ' + age + '일 전(' + last.date + ')이에요. 앱이 꺼져 있었다면 켤 때 따라잡아요.');
    if (recent.length === 1) note('ok', '기록 첫날이에요. 기록이 쌓이면 그래프가 그려져요.');

    $('valHistory').replaceChildren(...recent.map((r) => el('tr', {},
      el('td', {}, r.date),
      el('td', {}, fmtKrw(r.totalKrw)),
      el('td', { class: signClass(r.returnPct) }, fmtPct(r.returnPct, true)),
      el('td', {}, Number(r.nav).toFixed(2)),
      el('td', {}, fmtDd(r.drawdownPct)),
      el('td', {}, levelBadge(r.level, levels)))));
    renderHero();
  }

  /** 누적 수익률 선 그래프 (지수 - 100). 시간축은 날짜 간격대로, 마지막 값이 오르면 빨강·내리면 파랑. */
  function drawChart(points) {
    const box = $('valChart');
    if (points.length < 2) { box.replaceChildren(); return; }
    const W = Math.max(box.clientWidth || 640, 280), H = 180;
    const m = { l: 48, r: 56, t: 14, b: 24 };
    const xs = points.map((p) => Date.parse(p.date));
    const ys = points.map((p) => Number(p.nav) - 100);
    let lo = Math.min(0, ...ys), hi = Math.max(0, ...ys);
    if (hi - lo < 0.2) { hi += 0.1; lo -= 0.1; }
    const pad = (hi - lo) * 0.12; hi += pad; lo -= pad;
    const x = (t) => m.l + (t - xs[0]) / (xs[xs.length - 1] - xs[0] || 1) * (W - m.l - m.r);
    const y = (v) => m.t + (hi - v) / (hi - lo) * (H - m.t - m.b);
    const lastY = ys[ys.length - 1];
    const color = lastY > 0 ? 'var(--up)' : lastY < 0 ? 'var(--down)' : 'var(--ink-2)';

    const s = svg('svg', { viewBox: '0 0 ' + W + ' ' + H, role: 'img',
      'aria-label': '누적 수익률 ' + points[0].date + '부터 ' + points[points.length - 1].date + '까지, 마지막 ' + fmtPct(lastY, true) });
    const ticks = [hi - pad, 0, lo + pad].filter((v, i, a) => a.findIndex((w) => Math.abs(w - v) < 1e-9) === i);
    ticks.forEach((v) => {
      s.append(svg('line', { x1: m.l, x2: W - m.r, y1: y(v), y2: y(v), stroke: v === 0 ? 'var(--axis)' : 'var(--grid)', 'stroke-width': 1 }));
      const t = svg('text', { x: m.l - 8, y: y(v) + 4, 'text-anchor': 'end' }); t.textContent = fmtPct(v, true); s.append(t);
    });
    [[0, 'start'], [points.length - 1, 'end']].forEach(([i, anchor]) => {
      const t = svg('text', { x: x(xs[i]), y: H - 6, 'text-anchor': anchor }); t.textContent = md(points[i].date); s.append(t);
    });
    s.append(svg('path', { d: points.map((p, i) => (i ? 'L' : 'M') + x(xs[i]).toFixed(1) + ' ' + y(ys[i]).toFixed(1)).join(' '),
      fill: 'none', stroke: color, 'stroke-width': 2, 'stroke-linejoin': 'round', 'stroke-linecap': 'round' }));
    s.append(svg('circle', { cx: x(xs[xs.length - 1]), cy: y(lastY), r: 4, fill: color, stroke: 'var(--card)', 'stroke-width': 2 }));
    const lbl = svg('text', { x: x(xs[xs.length - 1]) + 8, y: y(lastY) + 4, 'text-anchor': 'start', style: 'fill:var(--ink);font-weight:600' });
    lbl.textContent = fmtPct(lastY, true); s.append(lbl);

    const cross = svg('line', { y1: m.t, y2: H - m.b, stroke: 'var(--axis)', 'stroke-width': 1, visibility: 'hidden' });
    const dot = svg('circle', { r: 4, fill: color, stroke: 'var(--card)', 'stroke-width': 2, visibility: 'hidden' });
    const hit = svg('rect', { x: m.l, y: 0, width: W - m.l - m.r, height: H, fill: 'transparent' });
    s.append(cross, dot, hit);
    hit.addEventListener('pointermove', (e) => {
      const r = s.getBoundingClientRect();
      const px = (e.clientX - r.left) * (W / r.width);
      let best = 0;
      xs.forEach((t, i) => { if (Math.abs(x(t) - px) < Math.abs(x(xs[best]) - px)) best = i; });
      const cx = x(xs[best]), cy = y(ys[best]);
      cross.setAttribute('x1', cx); cross.setAttribute('x2', cx); cross.setAttribute('visibility', 'visible');
      dot.setAttribute('cx', cx); dot.setAttribute('cy', cy); dot.setAttribute('visibility', 'visible');
      const p = points[best];
      showTip([p.date, '누적 ' + fmtPct(ys[best], true), '평가액 ' + fmtKrw(p.totalKrw)],
        r.left + cx * (r.width / W), r.top + cy * (r.height / H) - 10);
    });
    hit.addEventListener('pointerleave', () => { cross.setAttribute('visibility', 'hidden'); dot.setAttribute('visibility', 'hidden'); hideTip(); });
    box.replaceChildren(el('div', { class: 'chart' }, s));
  }

  async function loadValuation() {
    try {
      const res = await fetch('/api/valuation?days=60');
      if (!res.ok) throw new Error('HTTP ' + res.status);
      renderValuation(await res.json());
    } catch (e) {
      $('valMeta').textContent = '평가 기록 불러오기 실패: ' + e.message;
    }
  }

  // ── 잔고 입력 폼 ──

  function render(data) {
    state.portfolio = data;
    renderMeta();
    renderHero();
    renderHoldings();
    renderAlloc();
    fillForm(data);
    if (!data.asOf || data.snapshotAgeDays > 7) $('formBox').open = true;
    updateMakePlan();
  }

  function lineRow(symbol, qty, avg) {
    return el('tr', {},
      el('td', {}, input('symbol', symbol, 'text', '예: VTI')),
      el('td', {}, input('qty', qty, 'numeric', '0')),
      el('td', {}, input('avg', avg, 'decimal', '')));
  }

  function input(name, value, mode, placeholder) {
    const i = el('input', { 'data-field': name, inputmode: mode, placeholder: placeholder });
    if (mode === 'text') i.setAttribute('autocapitalize', 'characters');
    i.value = value == null ? '' : String(value);
    return i;
  }

  function fillForm(data) {
    $('asOf').value = todayKst();
    $('cashKrw').value = data.cash ? String(Number(data.cash.krw)) : '';
    $('cashUsd').value = data.cash ? String(Number(data.cash.usd)) : '';
    const prev = new Map((data.positions || []).map((p) => [p.symbol, p]));
    const rows = data.coreSymbols.map((s) => {
      const p = prev.get(s); prev.delete(s);
      return lineRow(s, p ? p.qty : '', p && p.avgPrice != null ? p.avgPrice : '');
    });
    prev.forEach((p) => rows.push(lineRow(p.symbol, p.qty, p.avgPrice == null ? '' : p.avgPrice)));
    $('lines').replaceChildren(...rows);
  }

  const clean = (s) => String(s || '').replace(/[,\s₩$원]/g, '');

  /** 폼 → 요청 본문. 화면에서 잡을 수 있는 실수는 서버에 보내기 전에 알려 준다. */
  function collectForm() {
    const errors = [];
    const num = (label, raw, integer) => {
      const s = clean(raw);
      if (s === '') return null;
      if (integer ? !/^\d+$/.test(s) : !/^\d+(\.\d+)?$/.test(s)) {
        errors.push(label + ': 숫자가 아님 (' + raw + ')');
        return undefined;
      }
      return Number(s);
    };
    const holdings = [];
    $('lines').querySelectorAll('tr').forEach((tr, i) => {
      const get = (f) => tr.querySelector('[data-field="' + f + '"]').value;
      const symbol = get('symbol').trim().toUpperCase();
      const qty = num((i + 1) + '번째 줄 수량', get('qty'), true);
      const avg = num((i + 1) + '번째 줄 평균 단가', get('avg'), false);
      if (symbol === '' && (qty == null || qty === 0)) return;
      holdings.push({ symbol, qty: qty == null ? 0 : qty, avgPrice: avg == null ? null : avg });
    });
    const body = {
      asOf: $('asOf').value || null,
      holdings,
      cashKrw: num('원화 현금', $('cashKrw').value, false) ?? 0,
      cashUsd: num('달러 현금', $('cashUsd').value, false) ?? 0,
    };
    return { body, errors };
  }

  function showMessage(kind, title, items) {
    const box = el('div', { class: 'notice ' + kind }, title);
    if (items && items.length) box.append(el('ul', { class: 'errors' }, items.map((t) => el('li', {}, t))));
    $('formMessage').replaceChildren(box);
  }

  async function save(ev) {
    if (ev) ev.preventDefault();
    const { body, errors } = collectForm();
    if (errors.length) { showMessage('bad', '입력을 확인해 주세요', errors); return; }
    $('save').disabled = true;
    try {
      const res = await fetch('/api/portfolio/snapshot', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json', 'X-Robo-Request': '1' },
        body: JSON.stringify(body),
      });
      const data = await res.json().catch(() => ({}));
      if (res.status === 400 && data.errors) { showMessage('bad', '저장하지 않았어요', data.errors); return; }
      if (!res.ok) { showMessage('bad', '저장 실패 (HTTP ' + res.status + ')'); return; }
      render(data);
      showMessage('ok', body.asOf + ' 잔고를 저장했어요');
    } catch (e) {
      showMessage('bad', '서버에 연결할 수 없어요: ' + e.message);
    } finally {
      $('save').disabled = false;
    }
  }

  // ── 오늘 할 일 (주문안) ──

  const STATUS = {
    SENT: ['ok', '발송 · 이대로 주문'], REJECTED: ['bad', '폐기 · 주문 금지'], DONE: ['muted', '완료'],
    EXPIRED: ['muted', '만료'], DRAFT: ['warn', '초안'],
  };
  const TYPE = { CORE: '코어 리밸런싱', INITIAL_BUILD: '분할 구축' };
  const REASON = {
    BAND_BREACH: '허용 범위 이탈', BAND_FIXED_BY_CASH: '현금으로 이탈 해소', CASH_DEPLOY: '쌓인 현금 투입',
    INITIAL_BUILD: '나눠서 목표로', PROFILE_SHIFT: '프로필 전환',
  };
  const OUTCOME_KIND = { SENT: 'ok', REJECTED: 'bad', NO_ACTION: 'ok', OPEN_EXISTS: 'warn', ALREADY_TODAY: 'warn' };

  function badge(status) {
    const [kind, text] = STATUS[status] || ['muted', status];
    return el('span', { class: 'badge ' + kind }, text);
  }

  function checksList(checks) {
    if (!checks || !checks.length) return el('div');
    return el('ul', { class: 'checks' }, checks.map((c) => el('li', { class: c.passed ? 'pass' : 'fail' },
      el('span', { class: 'mark' }, c.passed ? '✓' : '✗'), c.label, el('span', { class: 'muted' }, ' — ' + c.detail))));
  }

  /** 안전 검증은 한 줄로 접어 두고, 실패가 있으면 펼쳐 둔다. */
  function checksFold(checks) {
    if (!checks || !checks.length) return el('div');
    const failed = checks.filter((c) => !c.passed);
    const title = failed.length
      ? '✗ 안전 검증 ' + failed.length + '개 실패: ' + failed.map((c) => c.label).join(', ')
      : '✓ 안전 검증 ' + checks.length + '개 모두 통과';
    const d = el('details', { class: 'fold' }, el('summary', { class: failed.length ? 'bad' : '' }, title), checksList(checks));
    if (failed.length) d.open = true;
    return d;
  }

  function ticket(side, it) {
    const rate = state.portfolio && state.portfolio.fx ? Number(state.portfolio.fx.rate) : null;
    const i = info(it.symbol);
    return el('div', { class: 'ticket' },
      avatar(it.symbol),
      el('div', { class: 'body' },
        el('div', { class: 'title' }, it.symbol, el('span', { class: 'name' }, i.name)),
        el('div', { class: 'detail num' }, krw.format(it.qty) + '주 × 예상 ' + fmtUsd(it.estPrice))),
      el('div', { class: 'amount num' },
        el('span', { class: 'side ' + (side === 'SELL' ? 'sell' : 'buy') }, side === 'SELL' ? '매도' : '매수'),
        el('b', {}, fmtUsd(it.estAmount)),
        el('span', { class: 'meta' }, rate != null ? '약 ' + fmtKrw(Number(it.estAmount) * rate) : '')));
  }

  /** 주문 순서대로: 매도 → 환전 → 매수. 매도 대금과 환전한 달러로 매수한다. */
  function orderSheet(p) {
    const sells = p.items.filter((i) => i.side === 'SELL');
    const buys = p.items.filter((i) => i.side === 'BUY');
    if (!sells.length && !buys.length && p.fxUsd == null) return el('p', { class: 'meta' }, '주문 줄 없음');
    const out = [];
    let n = 0;
    if (sells.length) out.push(el('div', { class: 'step' }, (++n) + '. 먼저 팔기'), sells.map((i) => ticket('SELL', i)));
    if (p.fxUsd != null) {
      out.push(el('div', { class: 'step' }, (++n) + '. 환전'), el('div', { class: 'ticket' },
        avatar('현금'),
        el('div', { class: 'body' }, el('div', { class: 'title' }, '원화 → 달러'), el('div', { class: 'detail' }, '증권사 앱에서 환전')),
        el('div', { class: 'amount num' }, el('span', { class: 'side fx' }, '환전'), el('b', {}, fmtUsd(p.fxUsd)),
          el('span', { class: 'meta' }, '약 ' + fmtKrw(p.fxKrwEst)))));
    }
    if (buys.length) out.push(el('div', { class: 'step' }, (++n) + '. 그다음 사기'), buys.map((i) => ticket('BUY', i)));
    const sum = (xs) => xs.reduce((a, i) => a + Number(i.estAmount), 0);
    out.push(el('div', { class: 'totals num' },
      el('span', {}, '매도 합계', el('b', { class: 'down' }, fmtUsd(sum(sells)))),
      el('span', {}, '매수 합계', el('b', { class: 'up' }, fmtUsd(sum(buys))))));
    return el('div', {}, out);
  }

  function planHead(p) {
    let until = '';
    if (p.status === 'SENT' && p.expiresAt) {
      const left = daysBetween(todayKst(), p.expiresAt);
      until = ' · ' + md(p.expiresAt) + '까지 ' + (left > 0 ? '(' + left + '일 남음)' : left === 0 ? '(오늘까지)' : '(기한 지남)');
    }
    return el('div', {},
      el('div', { class: 'card-head', style: 'margin:0' },
        el('strong', {}, '#' + p.id + ' ' + (TYPE[p.type] || p.type)), badge(p.status)),
      el('div', { class: 'plan-sub' }, '주문일 ' + p.tradeDate + until + ' · ' + (REASON[p.reason] || p.reason)
        + (p.sizePct != null ? ' · 총자산의 ' + Number(p.sizePct).toFixed(2) + '%' : '')),
      p.note ? el('div', { class: 'meta' }, p.note) : '');
  }

  function planBlock(p) {
    const box = el('div', {}, planHead(p));
    if (p.status === 'REJECTED') {
      box.append(el('div', { class: 'notice bad' }, '안전 검증에 걸려 폐기된 계산이에요. 이대로 주문하지 마세요.'));
      box.append(checksFold(p.checks));
      box.append(el('details', { class: 'fold' }, el('summary', {}, '폐기된 계산 보기'), orderSheet(p)));
      return box;
    }
    box.append(orderSheet(p), checksFold(p.checks));
    if (p.status === 'SENT') {
      box.append(el('details', { class: 'exec' },
        el('summary', { class: 'btn primary' }, '주문을 넣었으면 체결 입력하기'), execForm(p)));
    }
    return box;
  }

  // ── 체결 입력 ──

  /** 증권사 앱에서 실제로 체결된 결과. 기본값은 계획 그대로이니 다르게 체결된 줄만 고친다. */
  function execForm(p) {
    const input = (field, value, step) => el('input', { 'data-field': field, value: value, inputmode: 'decimal', type: 'text', 'data-step': step || '' });
    const rows = p.items.map((it) => el('tr', { 'data-item': it.id },
      el('td', {}, el('span', { class: 'side ' + (it.side === 'SELL' ? 'sell' : 'buy') }, it.side === 'SELL' ? '매도' : '매수')),
      el('td', {}, el('strong', {}, it.symbol)),
      el('td', {}, krw.format(it.qty) + '주'),
      el('td', {}, input('qty', String(it.qty))), el('td', {}, input('price', Number(it.estPrice).toFixed(2))),
      el('td', {}, input('fee', '0'))));
    const fxRow = el('div', { class: 'grid' },
      el('div', {}, el('label', {}, '환전해서 받은 달러 (환전했을 때만)'), el('input', { id: 'fxUsd', inputmode: 'decimal', value: '' })),
      el('div', {}, el('label', {}, '환전에 쓴 원화'), el('input', { id: 'fxKrw', inputmode: 'decimal', value: '' })));
    const msg = el('div', { id: 'execMessage' });
    const btn = el('button', { type: 'submit', class: 'primary' }, '체결 결과 저장');
    const form = el('form', { id: 'execForm' },
      el('p', { class: 'meta' }, '증권사 앱의 체결 내역 그대로 입력하세요. 미체결이면 수량 0. 저장하면 주문안이 완료되고 잔고가 갱신돼요.'
        + (p.fxUsd != null ? ' 이 주문안의 환전 예상: ' + fmtUsd(p.fxUsd) + ' (약 ' + fmtKrw(p.fxKrwEst) + ')' : '')),
      el('div', { class: 'table-wrap' }, el('table', {},
        el('thead', {}, el('tr', {}, ['구분', '종목', '계획', '체결 수량', '체결 가격($)', '수수료($)'].map((h) => el('th', {}, h)))),
        el('tbody', {}, rows))),
      fxRow, el('div', { class: 'row-actions' }, btn), msg);
    form.addEventListener('submit', (ev) => { ev.preventDefault(); saveExecution(p.id, form, btn, msg); });
    return form;
  }

  function collectExecution(form) {
    const errors = [];
    const parse = (label, raw, integer) => {
      const s = clean(raw);
      if (s === '') return null;
      if (integer ? !/^\d+$/.test(s) : !/^\d+(\.\d+)?$/.test(s)) { errors.push(label + ': 숫자가 아님 (' + raw + ')'); return undefined; }
      return Number(s);
    };
    const fills = [];
    form.querySelectorAll('tr[data-item]').forEach((tr) => {
      const get = (f) => tr.querySelector('[data-field="' + f + '"]').value;
      const name = tr.children[1].textContent;
      const qty = parse(name + ' 체결 수량', get('qty'), true);
      fills.push({ itemId: Number(tr.getAttribute('data-item')), qty: qty == null ? null : qty,
        price: parse(name + ' 체결 가격', get('price'), false), fee: parse(name + ' 수수료', get('fee'), false) ?? 0 });
    });
    const fxUsd = parse('환전 달러', form.querySelector('#fxUsd').value, false);
    const fxKrw = parse('환전 원화', form.querySelector('#fxKrw').value, false);
    return { body: { fills, fx: fxUsd == null && fxKrw == null ? null : { usd: fxUsd ?? null, krw: fxKrw ?? null } }, errors };
  }

  async function saveExecution(planId, form, btn, msg) {
    const { body, errors } = collectExecution(form);
    const show = (kind, title, items) => {
      const box = el('div', { class: 'notice ' + kind }, title);
      if (items && items.length) box.append(el('ul', { class: 'errors' }, items.map((t) => el('li', {}, t))));
      msg.replaceChildren(box);
    };
    if (errors.length) { show('bad', '입력을 확인해 주세요', errors); return; }
    btn.disabled = true;
    try {
      const res = await fetch('/api/plans/' + planId + '/executions', {
        method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Robo-Request': '1' }, body: JSON.stringify(body) });
      const r = await res.json().catch(() => ({}));
      if (!res.ok) { show('bad', r.message || '저장 실패 (HTTP ' + res.status + ')', r.errors); return; }
      render(r.portfolio);
      await loadPlans();
      $('planMessage').replaceChildren(el('div', { class: 'notice ok' }, r.message));
      $('planChecks').replaceChildren(el('div'));
    } catch (e) {
      show('bad', '서버에 연결할 수 없어요: ' + e.message);
    } finally {
      btn.disabled = false;
    }
  }

  function renderPlans(o) {
    state.plans = o;
    const s = o.schedule;
    $('planSchedule').textContent = [
      '미국 주문일 ' + md(s.tradeDate),
      s.splitDay ? '분할 주문일' : '다음 분할 ' + md(s.nextSplitDay),
      s.rebalanceDay ? '월 정기 점검일' : '',
    ].filter(Boolean).join(' · ');
    $('planOpen').replaceChildren(o.open ? planBlock(o.open) : el('div', { class: 'empty' },
      el('b', {}, '지금은 주문할 게 없어요'),
      '매일 08:00에 계산해서 주문할 게 생기면 텔레그램으로 알려 드려요.'
        + (s.splitDay ? '' : ' 다음 분할 주문일은 ' + s.nextSplitDay + '이에요.')));
    $('planHistory').replaceChildren(...(o.recent || []).map((p) => el('tr', {},
      el('td', {}, '#' + p.id), el('td', {}, p.tradeDate), el('td', {}, TYPE[p.type] || p.type),
      el('td', {}, badge(p.status)), el('td', {}, REASON[p.reason] || p.reason),
      el('td', {}, p.sizePct == null ? '–' : Number(p.sizePct).toFixed(2) + '%'))));
    updateMakePlan();
  }

  /**
   * 직접 계산 버튼. 진행 중인 주문안이 있거나 시세가 최근 거래일보다 오래됐으면 막는다
   * (수집 전에 누르면 신선도 검증으로 폐기만 되므로). 매일 작업은 이 버튼과 관계없이 수집부터 한다.
   */
  function updateMakePlan() {
    const btn = $('makePlan'), hint = $('makePlanHint');
    const o = state.plans, data = state.portfolio;
    let why = '';
    if (o && o.open) why = '진행 중인 주문안이 있어요. 체결을 입력하거나 기한이 지나면 새로 계산할 수 있어요.';
    else if (o && data) {
      const dates = (data.valuation.rows || []).map((r) => r.priceDate);
      const oldest = dates.some((d) => !d) ? null : dates.slice().sort()[0];
      if (!dates.length || oldest == null) why = '시세가 없는 종목이 있어요. 수집이 끝난 뒤 다시 눌러 주세요.';
      else if (oldest < o.schedule.lastSession) {
        why = '시세가 ' + oldest + ' 기준이라 지금 계산하면 폐기돼요. 앱을 켠 직후라면 1~2분 뒤 자동으로 수집·계산해요.';
      }
    }
    btn.disabled = !!why || !o || !data;
    hint.textContent = why;
  }

  async function loadPlans() {
    try {
      const res = await fetch('/api/plans');
      if (!res.ok) throw new Error('HTTP ' + res.status);
      renderPlans(await res.json());
    } catch (e) {
      $('planSchedule').textContent = '주문안 불러오기 실패: ' + e.message;
    }
  }

  async function makePlan() {
    $('makePlan').disabled = true;
    try {
      const res = await fetch('/api/plans', { method: 'POST', headers: { 'X-Robo-Request': '1' } });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const r = await res.json();
      // 주문이 없어도 데이터 검증에 걸렸으면 초록색으로 안심시키지 않는다 (시작일 전은 데이터 문제가 아님)
      const dataFail = (r.checks || []).some((c) => !c.passed && c.rule !== 'GOAL_STARTED');
      const kind = r.outcome === 'NO_ACTION' && dataFail ? 'warn' : (OUTCOME_KIND[r.outcome] || 'warn');
      $('planMessage').replaceChildren(el('div', { class: 'notice ' + kind }, r.message));
      // 저장된 주문안은 위에 검증 결과와 함께 나오므로, 저장 안 된 결과(주문 없음)만 여기 보여 준다
      $('planChecks').replaceChildren(r.outcome === 'NO_ACTION' ? checksFold(r.checks) : el('div'));
      if (r.outcome === 'REJECTED' && r.plan) $('planChecks').replaceChildren(planBlock(r.plan));
    } catch (e) {
      $('planMessage').replaceChildren(el('div', { class: 'notice bad' }, '주문안을 만들 수 없어요: ' + e.message));
    } finally {
      await loadPlans();
    }
  }

  async function load() {
    try {
      const res = await fetch('/api/portfolio');
      if (!res.ok) throw new Error('HTTP ' + res.status);
      render(await res.json());
    } catch (e) {
      $('meta').textContent = '불러오기 실패: ' + e.message;
    }
  }

  let resizeTimer = 0;
  window.addEventListener('resize', () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => { if (state.valuation) drawChart([...(state.valuation.recent || [])].reverse()); }, 150);
  });

  $('form').addEventListener('submit', save);
  $('addLine').addEventListener('click', () => $('lines').append(lineRow('', '', '')));
  $('makePlan').addEventListener('click', makePlan);
  window.robo = { render, collectForm, save, load, loadPlans, makePlan, renderValuation, loadValuation, state };
  load();
  loadValuation();
  loadPlans();
})();
