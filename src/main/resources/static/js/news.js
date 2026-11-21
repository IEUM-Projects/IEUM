// 시장·로컬 소식 (NEWS-01): 주제 탭 + 최신 기사 30개
import * as auth from './auth.js';
import { mountAccount } from './header.js';
import { NEWS_NOTE, NEWS_TOPICS, newsItem } from './news-common.js';

const $ = (id) => document.getElementById(id);
let request = 0;

async function load(topic) {
  const mine = ++request;
  $('newsStatus').textContent = '불러오는 중...';
  $('newsList').replaceChildren();
  try {
    const res = await fetch(`/api/news?topic=${topic.key}&size=30`);
    if (!res.ok) throw new Error();
    const data = await res.json();
    if (mine !== request) return;
    $('newsList').replaceChildren(...data.items.map(newsItem));
    $('newsStatus').textContent = data.items.length
      ? `'${data.query}' 최신 기사 ${data.items.length}개`
      : data.available ? `'${data.query}' 기사가 아직 없어요.` : '지금은 기사를 불러올 수 없어요. 잠시 후 다시 시도해 주세요.';
  } catch (_) {
    if (mine === request) $('newsStatus').textContent = '기사를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.';
  }
}

function select(topic, focus) {
  document.querySelectorAll('#newsTabs [role="tab"]').forEach((t) => {
    const on = t.dataset.topic === topic.key;
    t.setAttribute('aria-selected', String(on));
    t.tabIndex = on ? 0 : -1;
    if (on && focus) t.focus();
  });
  $('newsPanel').setAttribute('aria-labelledby', `news-tab-${topic.key}`);
  history.replaceState(null, '', `/news.html?topic=${topic.key}`);
  load(topic);
}

function init() {
  mountAccount($('account'));
  auth.init();
  $('newsNote').textContent = `${NEWS_NOTE} 기사 내용은 각 언론사에 있고, 이음은 제목과 링크만 보여 드려요.`;
  const tabs = NEWS_TOPICS.map((t) => {
    const b = document.createElement('button');
    Object.assign(b, { type: 'button', id: `news-tab-${t.key}`, textContent: t.label });
    b.setAttribute('role', 'tab');
    b.setAttribute('aria-controls', 'newsPanel');
    b.dataset.topic = t.key;
    return b;
  });
  $('newsTabs').replaceChildren(...tabs);
  tabs.forEach((b, i) => {
    b.addEventListener('click', () => select(NEWS_TOPICS[i]));
    b.addEventListener('keydown', (e) => {
      const n = { ArrowRight: i + 1, ArrowLeft: i - 1, Home: 0, End: tabs.length - 1 }[e.key];
      if (n === undefined) return;
      e.preventDefault();
      select(NEWS_TOPICS[(n + tabs.length) % tabs.length], true);
    });
  });
  const want = new URLSearchParams(location.search).get('topic');
  select(NEWS_TOPICS.find((t) => t.key === want) ?? NEWS_TOPICS[0]);
}

init();
