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
      data.fx ? '환율 ' + usd.format(Number(data.fx.rate)) + '원 (' + data.fx.date + ')' : '환율 없음',
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

  // ── 주문안 ──

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

  /** 주문 순서대로: 매도 → 환전 → 매수. 매도 대금과 환전한 달러로 매수한다. */
  function linesTable(p) {
    const rows = [];
    let n = 0;
    const line = (side, it) => el('tr', {},
      el('td', {}, String(++n)), el('td', {}, side === 'SELL' ? '매도' : '매수'),
      el('td', {}, el('strong', {}, it.symbol)), el('td', {}, krw.format(it.qty) + '주'),
      el('td', {}, fmtUsd(it.estPrice)), el('td', {}, fmtUsd(it.estAmount)));
    p.items.filter((i) => i.side === 'SELL').forEach((i) => rows.push(line('SELL', i)));
    if (p.fxUsd != null) {
      rows.push(el('tr', { class: 'fx' }, el('td', {}, String(++n)), el('td', {}, '환전'),
        el('td', {}, '원화 → 달러'), el('td', {}, ''), el('td', {}, ''),
        el('td', {}, fmtUsd(p.fxUsd) + ' (약 ' + fmtKrw(p.fxKrwEst) + ')')));
    }
    p.items.filter((i) => i.side === 'BUY').forEach((i) => rows.push(line('BUY', i)));
    if (!rows.length) return el('p', { class: 'meta' }, '주문 줄 없음');
    return el('div', { class: 'table-wrap' }, el('table', {},
      el('thead', {}, el('tr', {}, ['순서', '구분', '종목', '수량', '예상 가격', '예상 금액'].map((h) => el('th', {}, h)))),
      el('tbody', {}, rows)));
  }

  function planBlock(p) {
    const head = el('div', { class: 'plan-head' },
      el('strong', {}, '#' + p.id + ' ' + (TYPE[p.type] || p.type)), badge(p.status),
      el('span', { class: 'meta' }, '주문일 ' + p.tradeDate + (p.status === 'SENT' ? ' · ' + p.expiresAt + '까지 유효' : '')
        + ' · ' + (REASON[p.reason] || p.reason) + (p.sizePct != null ? ' · 총자산의 ' + Number(p.sizePct).toFixed(2) + '%' : '')));
    const box = el('div', {}, head);
    if (p.note) box.append(el('p', { class: 'meta' }, p.note));
    if (p.status === 'REJECTED') {
      box.append(el('div', { class: 'notice bad' }, '안전 검증에 걸려 폐기된 계산이에요. 이대로 주문하지 마세요.'));
      box.append(el('details', {}, el('summary', { class: 'meta' }, '폐기된 계산 보기'), linesTable(p)));
    } else {
      box.append(linesTable(p));
    }
    box.append(checksList(p.checks));
    return box;
  }

  function renderPlans(o) {
    const s = o.schedule;
    $('planSchedule').textContent = [
      '주문일(미국) ' + s.tradeDate,
      s.rebalanceDay ? '월 정기 점검일' : '정기 점검일 아님',
      s.splitDay ? '분할 주문일' : '다음 분할 주문일 ' + s.nextSplitDay,
    ].join(' · ');
    $('planOpen').replaceChildren(o.open ? planBlock(o.open) : el('p', { class: 'meta' }, '진행 중인 주문안 없음'));
    $('makePlan').disabled = !!o.open;
    $('planHistory').replaceChildren(...(o.recent || []).map((p) => el('tr', {},
      el('td', {}, '#' + p.id), el('td', {}, p.tradeDate), el('td', {}, TYPE[p.type] || p.type),
      el('td', {}, badge(p.status)), el('td', {}, REASON[p.reason] || p.reason),
      el('td', {}, p.sizePct == null ? '–' : Number(p.sizePct).toFixed(2) + '%'))));
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
      // 저장된 주문안은 위 목록에 검증 결과와 함께 나오므로, 저장 안 된 결과(주문 없음)만 여기 보여 준다
      $('planChecks').replaceChildren(r.outcome === 'NO_ACTION' ? checksList(r.checks) : el('div'));
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

  $('form').addEventListener('submit', save);
  $('addLine').addEventListener('click', () => $('lines').append(lineRow('', '', '')));
  $('makePlan').addEventListener('click', makePlan);
  window.robo = { render, collectForm, save, load, loadPlans, makePlan };
  load();
  loadPlans();
})();
