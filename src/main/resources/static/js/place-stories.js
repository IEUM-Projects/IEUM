// 가게·시장·축제 화면의 '방문 이야기' (COM-01): 그곳에 붙은 최근 이야기 3개와 더 보기·쓰기 링크
import { el, posts, when, authorText, categoryBadge } from './community-common.js';

/** root 끝에 붙인다. type: STORE / MARKET / FESTIVAL */
export async function appendPlaceStories(root, { type, id, name }) {
  const section = el('section', { class: 'place-stories', 'aria-labelledby': 'placeStoriesTitle' },
    el('h2', { id: 'placeStoriesTitle', text: '방문 이야기' }));
  const status = el('p', { class: 'place-stories-status', text: '불러오는 중...' });
  section.append(status);
  root.append(section);

  const listUrl = `/community.html?${new URLSearchParams({ placeType: type, placeId: id, placeName: name })}`;
  const writeUrl = `/post-write.html?${new URLSearchParams({ category: 'REVIEW', placeType: type, placeId: id })}`;
  try {
    const data = await posts.list({ placeType: type, placeId: id });
    const items = data.items.slice(0, 3);
    status.textContent = items.length ? '' : '아직 이야기가 없어요. 다녀오셨다면 첫 이야기를 남겨 주세요.';
    status.hidden = !!items.length;
    if (items.length) {
      section.append(el('ul', { class: 'place-stories-list' }, items.map((p) => el('li', {},
        el('p', { class: 'place-stories-head' }, categoryBadge(p.category, p.categoryLabel),
          el('a', { href: `/post.html?id=${p.id}`, text: p.title })),
        el('p', { class: 'place-stories-meta', text: [authorText(p.author), when(p.createdAt), `공감 ${p.likeCount}`, `댓글 ${p.commentCount}`].join(' · ') })))));
    }
    section.append(el('p', { class: 'place-stories-links' },
      data.total > 3 ? el('a', { href: listUrl, text: `이야기 ${data.total}개 모두 보기` }) : null,
      el('a', { href: writeUrl, text: '이야기 쓰기' })));
  } catch (_) {
    status.textContent = '이야기를 불러오지 못했어요.';
  }
}
