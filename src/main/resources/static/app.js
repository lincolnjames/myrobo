// 잔고 입력 화면. 서버 API: GET /api/portfolio, PUT /api/portfolio/snapshot
// 변경 요청에는 X-Robo-Request 헤더가 필요하다 (CSRF 방어, LocalRequestGuard 참고).
(function () {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const krw = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 0 });
  const usd = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

  const fmtKrw = (v) => (v == null ? '–' : '₩' + krw.format(Number(v)));
  const fmtUsd = (v) => (v == null ? '–' : '$' + usd.format(Number(v)));
  const fmtPct = (v, signed) => {
    if (v == null) return '–';
    const n = Number(v);
    return (signed && n > 0 ? '+' : '') + n.toFixed(2) + '%';
  };
  const todayKst = () => new Date(Date.now() + 9 * 3600 * 1000).toISOString().slice(0, 10);

  function el(tag, attrs, ...children) {
    const e = document.createElement(tag);
    Object.entries(attrs || {}).forEach(([k, v]) => {
      if (k === 'class') e.className = v; else e.setAttribute(k, v);
    });
    children.flat().forEach((c) => e.append(c instanceof Node ? c : document.createTextNode(c == null ? '' : String(c))));
    return e;
  }

  function notice(kind, text) {
    $('notices').append(el('div', { class: 'notice ' + kind }, text));
  }

  // ── 화면 그리기 ──

  function render(data) {
    const v = data.valuation;
    $('notices').replaceChildren();

    const priceDates = (v.rows || []).map((r) => r.priceDate).filter(Boolean).sort();
    $('meta').textContent = [
      '증권사 ' + data.broker,
      '목표 기간 ' + data.horizon,
      '잔고 기준일 ' + (data.asOf || '없음'),
      '시세 기준일 ' + (priceDates.length ? priceDates[0] : '없음'),
      data.fx ? '환율 ' + krw.format(Number(data.fx.rate)) + '원 (' + data.fx.date + ')' : '환율 없음',
    ].join(' · ');

    if (!data.asOf) notice('warn', '아직 입력된 잔고가 없어요. 아래에서 오늘 잔고를 입력하세요.');
    else if (data.snapshotAgeDays > 7) notice('bad', '잔고가 ' + data.snapshotAgeDays + '일 전 기록이에요. 7일이 지나면 주문안이 만들어지지 않아요.');
    if (v.missing && v.missing.length) notice('warn', '비중을 계산할 수 없어요. 없는 데이터: ' + v.missing.join(', ') + ' (수집을 먼저 돌려 주세요)');
    else if (v.totalUsd != null) {
      const n = v.rows.filter((r) => r.breach).length;
      notice(n ? 'bad' : 'ok', n ? '허용 범위를 벗어난 종목 ' + n + '개' : '모든 종목이 허용 범위 안에 있어요');
    }

    $('summary').replaceChildren(
      stat('총자산 (원)', fmtKrw(v.totalKrw)),
      stat('총자산 (달러)', fmtUsd(v.totalUsd)),
      stat('현금 (달러 환산)', fmtUsd(v.cashTotalUsd)),
    );

    const rows = (v.rows || []).map((r) => el('tr', {},
      el('td', {}, el('strong', {}, r.symbol)),
      el('td', {}, krw.format(r.qty)),
      el('td', {}, r.price == null ? '–' : fmtUsd(r.price)),
      el('td', {}, fmtUsd(r.valueUsd)),
      el('td', {}, fmtPct(r.weightPct)),
      el('td', {}, fmtPct(r.targetPct)),
      el('td', {}, fmtPct(r.deviationPct, true)),
      el('td', { class: 'muted' }, r.bandPct == null ? '–' : '±' + Number(r.bandPct).toFixed(2) + '%p'),
      el('td', {}, r.weightPct == null ? '–' : el('span', { class: 'badge ' + (r.breach ? 'bad' : 'ok') }, r.breach ? '이탈' : '정상')),
    ));
    rows.push(el('tr', {},
      el('td', {}, '현금'), el('td', {}, ''), el('td', {}, ''),
      el('td', {}, fmtUsd(v.cashTotalUsd)),
      el('td', {}, fmtPct(v.cashWeightPct)), el('td', {}, fmtPct(v.cashTargetPct)),
      el('td', {}, ''), el('td', {}, ''), el('td', {}, '')));
    $('weights').replaceChildren(...rows);

    $('excluded').textContent = v.excluded && v.excluded.length
      ? '계산에서 제외된 보유 종목(시스템 밖): ' + v.excluded.map((p) => p.symbol + ' ' + p.qty + '주').join(', ')
      : '';

    fillForm(data);
  }

  function stat(label, value) {
    return el('div', { class: 'stat' }, el('div', { class: 'label' }, label), el('div', { class: 'value' }, value));
  }

  // ── 입력 폼 ──

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

  const clean = (s) => String(s || '').replace(/[,\s₩$]/g, '');

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

  async function load() {
    try {
      const res = await fetch('/api/portfolio');
      if (!res.ok) throw new Error('HTTP ' + res.status);
      render(await res.json());
    } catch (e) {
      $('meta').textContent = '불러오기 실패: ' + e.message;
    }
  }

  $('form').addEventListener('submit', save);
  $('addLine').addEventListener('click', () => $('lines').append(lineRow('', '', '')));
  window.robo = { render, collectForm, save, load };
  load();
})();
