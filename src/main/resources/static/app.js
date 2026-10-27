// 잔고 입력 화면. 서버 API: GET /api/portfolio, PUT /api/portfolio/snapshot, GET /api/valuation
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

  function stat(label, value, sub) {
    const s = el('div', { class: 'stat' }, el('div', { class: 'label' }, label), el('div', { class: 'value' }, value));
    if (sub) s.append(el('div', { class: 'meta' }, sub));
    return s;
  }

  // ── 평가액과 낙폭 ──

  const LEVEL = { ALERT: ['warn', '경보'], STOP_SATELLITE_BUYS: ['bad', '위성 매수 중단'], LIMIT: ['bad', '낙폭 한도 도달'] };
  const fmtDd = (v) => (v == null ? '–' : Number(v) === 0 ? '0.00%' : '-' + Number(v).toFixed(2) + '%');
  const daysBetween = (a, b) => Math.round((Date.parse(b) - Date.parse(a)) / 86400000);

  function levelBadge(level, levels) {
    if (!level) return el('span', { class: 'badge ok' }, '정상');
    const l = levels.find((x) => x.level === level);
    const [kind, text] = l ? (LEVEL[l.action] || ['warn', l.action]) : ['warn', '단계 ' + level];
    return el('span', { class: 'badge ' + kind }, text + (l ? ' · ' + Number(l.atPct) + '%' : ''));
  }

  function renderValuation(o) {
    const levels = o.levels || [];
    const recent = o.recent || [];
    $('valMeta').textContent = '입출금·매매 영향을 뺀 지수(첫 기록 = 100)가 가장 높았던 때보다 얼마나 내려왔는지예요. '
      + '경보선 ' + levels.map((l) => Number(l.atPct) + '%').join(' · ') + ' (마지막은 목표의 최대 낙폭 한도) · 매일 08:00 작업이 기록해요.';
    $('valNotice').replaceChildren();
    const note = (kind, text) => $('valNotice').append(el('div', { class: 'notice ' + kind }, text));

    const last = recent[0];
    if (!last) {
      $('valSummary').replaceChildren();
      $('valHistory').replaceChildren();
      note('warn', '아직 기록이 없어요. 잔고를 입력하면 다음 매일 작업부터 기록돼요.');
      return;
    }
    $('valSummary').replaceChildren(
      stat('고점 대비', fmtDd(last.drawdownPct), '고점 ' + last.peakDate),
      stat('경보 단계', levelBadge(last.level, levels)),
      stat('지수 (시작 100)', Number(last.nav).toFixed(2), last.date + ' 기록'),
      stat('직전 대비', fmtPct(last.returnPct, true)),
    );

    if (last.level > 0) {
      const l = levels.find((x) => x.level === last.level);
      note(LEVEL[l && l.action] ? LEVEL[l.action][0] : 'warn', '고점(' + last.peakDate + ') 대비 ' + Number(last.drawdownPct).toFixed(2)
        + '% 내려와 있어요. 규칙은 그대로예요. 주문은 발송된 주문안대로만 넣으세요.');
    }
    const age = daysBetween(last.date, todayKst());
    if (age > 1) note('warn', '최근 기록이 ' + age + '일 전(' + last.date + ')이에요. 앱이 꺼져 있었다면 켤 때 따라잡아요.');
    if (recent.length === 1) note('ok', '기록 첫날이에요. 낙폭은 기록이 쌓이면서 의미가 생겨요.');

    $('valHistory').replaceChildren(...recent.map((r) => el('tr', {},
      el('td', {}, r.date),
      el('td', {}, fmtKrw(r.totalKrw)),
      el('td', {}, fmtPct(r.returnPct, true)),
      el('td', {}, Number(r.nav).toFixed(2)),
      el('td', {}, fmtDd(r.drawdownPct)),
      el('td', {}, levelBadge(r.level, levels)))));
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
    if (p.status === 'SENT') box.append(execForm(p));
    return box;
  }

  // ── 체결 입력 ──

  /** 증권사 앱에서 실제로 체결된 결과. 기본값은 계획 그대로이니 다르게 체결된 줄만 고친다. */
  function execForm(p) {
    const input = (field, value, step) => el('input', { 'data-field': field, value: value, inputmode: 'decimal', type: 'text', 'data-step': step || '' });
    const rows = p.items.map((it) => el('tr', { 'data-item': it.id },
      el('td', {}, it.side === 'SELL' ? '매도' : '매수'), el('td', {}, el('strong', {}, it.symbol)),
      el('td', {}, krw.format(it.qty) + '주'),
      el('td', {}, input('qty', String(it.qty))), el('td', {}, input('price', Number(it.estPrice).toFixed(2))),
      el('td', {}, input('fee', '0'))));
    const fxRow = el('div', { class: 'grid' },
      el('div', {}, el('label', {}, '환전해서 받은 달러 (환전했을 때만)'), el('input', { id: 'fxUsd', inputmode: 'decimal', value: '' })),
      el('div', {}, el('label', {}, '환전에 쓴 원화'), el('input', { id: 'fxKrw', inputmode: 'decimal', value: '' })));
    const msg = el('div', { id: 'execMessage' });
    const btn = el('button', { type: 'submit', class: 'primary' }, '체결 결과 저장');
    const form = el('form', { id: 'execForm' },
      el('h2', { style: 'margin-top:18px' }, '체결 입력'),
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
  window.robo = { render, collectForm, save, load, loadPlans, makePlan, renderValuation, loadValuation };
  load();
  loadValuation();
  loadPlans();
})();
