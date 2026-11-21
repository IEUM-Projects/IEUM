// 지역 축제 달력 (FES-01): /festivals.html?month=2026-11&sido=11&date=2026-11-13
import { api } from './api.js';
import * as auth from './auth.js';
import { mountAccount } from './header.js';
import { WEEKDAYS, pad, ymd, daysIn, weekday, todayString, periodText, status } from './festival-format.js';

const $ = (id) => document.getElementById(id);

const state = { year: 0, month: 0, sido: '', date: null, festivals: [], loading: false };

function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  Object.entries(props).forEach(([key, value]) => {
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else node.setAttribute(key, value);
  });
  children.flat().filter(Boolean).forEach((child) => node.append(child));
  return node;
}

const covers = (f, day) => f.startDate <= day && f.endDate >= day;

// ---------------------------------------------------------------- 주소창 ↔ 상태

function readUrl() {
  const params = new URLSearchParams(location.search);
  const today = todayString();
  const month = /^\d{4}-\d{2}$/.test(params.get('month') ?? '') ? params.get('month') : today.slice(0, 7);
  [state.year, state.month] = month.split('-').map(Number);
  if (state.month < 1 || state.month > 12) [state.year, state.month] = today.slice(0, 7).split('-').map(Number);
  state.sido = /^\d{2}$/.test(params.get('sido') ?? '') ? params.get('sido') : '';
  const date = params.get('date');
  // 날짜를 고르지 않았으면, 이번 달을 볼 때는 오늘을 골라 둔다
  if (date && date.startsWith(`${state.year}-${pad(state.month)}-`) && /^\d{4}-\d{2}-\d{2}$/.test(date)) state.date = date;
  else state.date = today.startsWith(`${state.year}-${pad(state.month)}`) && !params.has('month') ? today : null;
}

function writeUrl() {
  const params = new URLSearchParams({ month: `${state.year}-${pad(state.month)}` });
  if (state.sido) params.set('sido', state.sido);
  if (state.date) params.set('date', state.date);
  history.replaceState(null, '', `/festivals.html?${params}`);
}

// ---------------------------------------------------------------- 그리기

function renderCalendar() {
  const { year, month } = state;
  $('monthTitle').textContent = `${year}년 ${month}월`;
  const today = todayString();
  const short = state.festivals.filter((f) => !f.longTerm);
  const first = ymd(year, month, 1);
  const blanks = (weekday(first) + 6) % 7;   // 월요일 시작
  const cells = [];
  for (let i = 0; i < blanks; i++) cells.push(el('span', { class: 'day empty', 'aria-hidden': 'true' }));
  for (let d = 1; d <= daysIn(year, month); d++) {
    const day = ymd(year, month, d);
    const count = short.filter((f) => covers(f, day)).length;
    const wd = weekday(day);
    const button = el('button', {
      type: 'button',
      class: `day${wd === 0 ? ' sun' : ''}${wd === 6 ? ' sat' : ''}${count ? ' has' : ''}`,
      'aria-pressed': String(state.date === day),
      'aria-label': `${month}월 ${d}일 ${WEEKDAYS[wd]}요일, ${count ? `축제 ${count}개` : '축제 없음'}${day === today ? ', 오늘' : ''}`,
      'data-date': day,
    }, el('span', { class: 'num', text: String(d) }), count ? el('span', { class: 'count', text: String(count) }) : null);
    if (day === today) button.setAttribute('aria-current', 'date');
    button.addEventListener('click', () => selectDate(state.date === day ? null : day));
    cells.push(button);
  }
  $('calendar').replaceChildren(...cells);
}

function festivalItem(f) {
  const s = status(f);
  const where = [f.regionName, f.place].filter(Boolean).join(' · ');
  return el('li', {},
    el('a', { href: `/festival.html?id=${f.id}`, class: 'festival-item' },
      el('span', { class: 'item-top' },
        el('span', { class: `status ${s.cls}`, text: s.text }),
        el('span', { class: 'period', text: periodText(f) }),
      ),
      el('strong', { class: 'name', text: f.name }),
      where ? el('span', { class: 'where', text: where }) : null,
    ));
}

function renderList() {
  const inScope = state.date ? state.festivals.filter((f) => covers(f, state.date)) : state.festivals;
  const short = inScope.filter((f) => !f.longTerm);
  const long = inScope.filter((f) => f.longTerm);
  const label = state.date ? `${Number(state.date.slice(5, 7))}월 ${Number(state.date.slice(8))}일(${WEEKDAYS[weekday(state.date)]})` : `${state.month}월`;
  $('listTitle').textContent = `${label} 축제 ${short.length}개`;
  $('clearDate').hidden = !state.date;
  $('festivalList').replaceChildren(...short.map(festivalItem));
  $('listStatus').textContent = state.loading ? '불러오는 중...'
    : short.length ? ''
      : '이 기간에 등록된 축제가 없어요. 지자체가 일정을 확정한 뒤 올리기 때문에, 몇 달 뒤 축제는 아직 없을 수 있어요.';
  $('longTerm').hidden = long.length === 0;
  $('longTermTitle').textContent = `기간 중 계속 열리는 행사 ${long.length}개`;
  $('longTermList').replaceChildren(...long.map(festivalItem));
}

// ---------------------------------------------------------------- 동작

function selectDate(day) {
  state.date = day;
  writeUrl();
  renderCalendar();
  renderList();
}

async function load() {
  const from = ymd(state.year, state.month, 1);
  const to = ymd(state.year, state.month, daysIn(state.year, state.month));
  state.loading = true;
  state.festivals = [];
  renderCalendar();
  renderList();
  try {
    state.festivals = await api.festivals({ from, to, sido: state.sido });
    state.loading = false;
  } catch (e) {
    state.loading = false;
    renderCalendar();
    renderList();
    $('listStatus').textContent = '축제 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.';
    return;
  }
  renderCalendar();
  renderList();
}

function moveMonth(diff) {
  let m = state.month + diff;
  let y = state.year;
  if (m < 1) { m = 12; y--; }
  if (m > 12) { m = 1; y++; }
  state.year = y;
  state.month = m;
  state.date = null;
  writeUrl();
  load();
}

async function fillRegions() {
  try {
    const regions = await api.regions();
    $('sidoSelect').append(...regions.map((r) => el('option', { value: r.sidoCode, text: r.sidoName })));
    $('sidoSelect').value = state.sido;
  } catch (_) { /* 지역 목록이 없어도 전국으로 볼 수 있다 */ }
}

async function init() {
  mountAccount($('account'));
  auth.init();
  readUrl();
  $('prevMonth').addEventListener('click', () => moveMonth(-1));
  $('nextMonth').addEventListener('click', () => moveMonth(1));
  $('clearDate').addEventListener('click', () => selectDate(null));
  $('sidoSelect').addEventListener('change', (event) => {
    state.sido = event.target.value;
    writeUrl();
    load();
  });
  await fillRegions();
  writeUrl();
  await load();
}

init();
