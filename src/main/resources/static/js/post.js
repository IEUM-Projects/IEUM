// 동네 이야기 글 보기 (COM-01): 본문, 공감, 고치기·지우기(글쓴이), 신고, 댓글
import * as auth from './auth.js';
import { mountAccount } from './header.js';
import { el, posts, when, categoryBadge, authorText, placeLink, REPORT_REASONS, toast } from './community-common.js';

const $ = (id) => document.getElementById(id);
const id = new URLSearchParams(location.search).get('id');
let post = null;
let user = null;
let reportTarget = null;
let reportOpener = null;

function render(p) {
  post = p;
  document.title = `${p.title} - 동네 이야기 - 이음`;
  $('postBadges').replaceChildren(...[categoryBadge(p.category, p.categoryLabel),
    p.recommendStatusLabel ? el('span', { class: `rec-badge rec-${p.recommendStatus.toLowerCase()}`, text: p.recommendStatusLabel }) : null].filter(Boolean));
  $('postTitle').textContent = p.title;
  $('postMeta').replaceChildren(authorText(p.author), ' · ', el('time', { datetime: p.createdAt, text: when(p.createdAt) }),
    p.editedAt ? ` · 고침 ${when(p.editedAt)}` : '');
  $('postPlace').replaceChildren(...(p.place ? [placeLink(p.place)] : []));
  $('postBody').textContent = p.content;
  // 사진 (COM-02): 설명이 없으면 '글 제목 사진 1/3'
  const ph = p.photos ?? [];
  $('postPhotos').replaceChildren(...ph.map((x, i) => el('figure', { class: 'post-photo' },
    el('img', { src: x.url, alt: x.alt || `${p.title} 사진 ${i + 1}/${ph.length}`, width: String(x.width), height: String(x.height), loading: i ? 'lazy' : 'eager', decoding: 'async' }))));
  $('postPhotos').hidden = ph.length === 0;
  $('hiddenNote').hidden = p.status === 'VISIBLE';
  if (p.status !== 'VISIBLE') {
    $('hiddenNote').textContent = `${p.status === 'TEMP_HIDDEN' ? '임시로 가려진 글이에요' : '가려진 글이에요'} (나와 관리자만 볼 수 있어요). ${p.hiddenReason ?? ''}`;
  }
  $('recNote').hidden = !(p.category === 'RECOMMEND' && p.recommendStatus && p.recommendStatus !== 'PENDING');
  $('recNote').textContent = p.recommendNote ? `${p.recommendStatusLabel} · ${p.recommendNote}` : (p.recommendStatusLabel ?? '');
  $('likeButton').setAttribute('aria-pressed', String(p.likedByMe));
  $('likeCount').textContent = String(p.likeCount);
  $('likeButton').setAttribute('aria-label', `공감 ${p.likeCount}개${p.likedByMe ? ', 공감함' : ''}`);
  $('likeButton').disabled = p.author.mine || p.status !== 'VISIBLE';
  $('editLink').hidden = !p.canEdit;
  $('editLink').href = `/post-write.html?id=${p.id}`;
  $('deleteButton').hidden = !(p.author.mine || user?.role === 'ADMIN');
  $('reportButton').hidden = !user || p.author.mine || p.status !== 'VISIBLE';
  $('post').hidden = false;
  renderComments(p);
}

function renderComments(p) {
  $('commentsTitle').textContent = `댓글 ${p.commentCount}`;
  $('commentList').replaceChildren(...p.comments.map((c) => {
    const placeholder = c.state === 'DELETED' ? '지운 댓글이에요.' : '관리자가 가린 댓글이에요.';
    const actions = [];
    // 댓글 고치기 (COM-04): 내 댓글이고 글이 보일 때만
    if (c.state === 'VISIBLE' && c.author.mine && p.status === 'VISIBLE') {
      const b = el('button', { type: 'button', class: 'text-button comment-edit', text: '고치기', 'aria-label': '내 댓글 고치기', 'aria-expanded': 'false' });
      b.addEventListener('click', () => openEdit(c, b));
      actions.push(b);
    }
    if (c.state === 'VISIBLE' && (c.author.mine || user?.role === 'ADMIN')) {
      const b = el('button', { type: 'button', class: 'text-button', text: '지우기', 'aria-label': '이 댓글 지우기' });
      b.addEventListener('click', () => confirmDelete('댓글을 지울까요?', async () => {
        render(await posts.removeComment(c.id));
        toast('댓글을 지웠어요.');
        $('commentText').focus();
      }));
      actions.push(b);
    }
    if (c.state === 'VISIBLE' && user && !c.author.mine) {
      const b = el('button', { type: 'button', class: 'text-button', text: '신고', 'aria-label': `${c.author.nickname}님의 댓글 신고` });
      b.addEventListener('click', () => openReport({ targetType: 'COMMENT', targetId: c.id, label: `${c.author.nickname}님의 댓글` }, b));
      actions.push(b);
    }
    return el('li', { class: `comment${c.content === null ? ' comment-gone' : ''}`, 'data-id': String(c.id) },
      el('p', { class: 'comment-meta' }, el('strong', { text: authorText(c.author) }), ' · ', el('time', { datetime: c.createdAt, text: when(c.createdAt) }),
        c.editedAt && c.content !== null ? [' · ', el('span', { class: 'comment-edited', title: `${when(c.editedAt)} 고침`, text: '수정됨' })] : null),
      el('p', { class: 'comment-body', text: c.content ?? placeholder }),
      actions.length ? el('p', { class: 'comment-actions' }, actions) : null);
  }));
  const canWrite = !!user && p.status === 'VISIBLE';
  $('commentForm').hidden = !canWrite;
  $('commentLogin').hidden = !!user || p.status !== 'VISIBLE';
  $('commentLoginLink').href = auth.loginUrl();
  $('comments').hidden = false;
}

// ---------------------------------------------------------------- 댓글 고치기 (COM-04)
// 댓글 자리에서 바로 고친다. 한 번에 하나만 연다.
function openEdit(c, opener) {
  document.querySelector('.comment-edit-form')?.querySelector('.edit-cancel')?.click();
  const li = opener.closest('li');
  const body = li.querySelector('.comment-body');
  const actions = li.querySelector('.comment-actions');
  const textId = `editText-${c.id}`;
  const text = el('textarea', { id: textId, maxlength: '1000', rows: '3', 'aria-describedby': `editHelp-${c.id} editError-${c.id}` });
  text.value = c.content;
  const count = el('span', { text: String(c.content.length) });
  const error = el('p', { id: `editError-${c.id}`, class: 'error', role: 'alert' });
  const save = el('button', { type: 'submit', class: 'button-primary', text: '고친 댓글 저장' });
  const cancel = el('button', { type: 'button', class: 'button-secondary edit-cancel', text: '그만두기' });
  const form = el('form', { class: 'comment-edit-form', novalidate: true },
    el('label', { for: textId, text: '댓글 고치기' }), text,
    el('p', { id: `editHelp-${c.id}`, class: 'help' }, count, '/1000자 · 고치면 ‘수정됨’이 붙어요.'),
    error, el('div', { class: 'edit-actions' }, cancel, save));
  text.addEventListener('input', () => { count.textContent = String(text.value.length); });
  const close = () => {
    form.remove();
    body.hidden = false;
    actions.hidden = false;
    opener.setAttribute('aria-expanded', 'false');
    opener.focus();
  };
  cancel.addEventListener('click', close);
  form.addEventListener('keydown', (e) => { if (e.key === 'Escape') { e.preventDefault(); close(); } });
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const value = text.value.trim();
    if (!value) { error.textContent = '댓글을 써 주세요.'; text.focus(); return; }
    if (value === c.content) { close(); return; }
    save.disabled = true;
    try {
      render(await posts.editComment(c.id, value));
      toast('댓글을 고쳤어요.');
      $('commentList').querySelector(`li[data-id="${c.id}"] .comment-edit`)?.focus();
    } catch (err) {
      save.disabled = false;
      if (err.status === 401) { location.href = auth.loginUrl(); return; }
      error.textContent = err.details?.length ? err.details.join(' ') : err.message;
      text.focus();
    }
  });
  body.hidden = true;
  actions.hidden = true;
  opener.setAttribute('aria-expanded', 'true');
  li.append(form);
  text.focus();
  text.setSelectionRange(text.value.length, text.value.length);
}

// ---------------------------------------------------------------- 지우기 확인 (브라우저 confirm 대신)
let confirmAction = null;
function confirmDelete(title, action) {
  confirmAction = action;
  $('confirmTitle').textContent = title;
  $('confirmDialog').showModal();
}

$('confirmDialog').addEventListener('close', async () => {
  if ($('confirmDialog').returnValue !== 'ok' || !confirmAction) return;
  try {
    await confirmAction();
  } catch (e) {
    toast(e.message);
  }
  confirmAction = null;
});

// ---------------------------------------------------------------- 신고
function openReport(target, opener) {
  reportTarget = target;
  reportOpener = opener;
  $('reportTarget').textContent = `${target.label}을(를) 신고해요. 관리자가 확인해서 가릴지 정해요.`;
  $('reportReasons').replaceChildren(...REPORT_REASONS.map((r) => el('label', { class: 'radio' },
    el('input', { type: 'radio', name: 'reason', value: r.code }), r.label)));
  $('reportText').value = '';
  $('reportError').textContent = '';
  $('reportDialog').showModal();
  $('reportReasons').querySelector('input').focus();
}

$('reportCancel').addEventListener('click', () => $('reportDialog').close());
$('reportDialog').addEventListener('close', () => reportOpener?.focus());
$('reportForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const reason = $('reportForm').querySelector('input[name="reason"]:checked')?.value;
  if (!reason) { $('reportError').textContent = '까닭을 골라 주세요.'; return; }
  $('reportSubmit').disabled = true;
  try {
    await posts.report({ targetType: reportTarget.targetType, targetId: reportTarget.targetId, reason, content: $('reportText').value.trim() });
    $('reportDialog').close();
    toast('신고했어요. 관리자가 확인할게요.');
  } catch (err) {
    $('reportError').textContent = err.message;
  } finally {
    $('reportSubmit').disabled = false;
  }
});

// ---------------------------------------------------------------- 공감, 지우기, 댓글
$('likeButton').addEventListener('click', async () => {
  if (!user) { location.href = auth.loginUrl(); return; }
  try {
    const r = await posts.like(post.id, !post.likedByMe);
    render({ ...post, likedByMe: r.liked, likeCount: r.likeCount });
  } catch (e) {
    toast(e.message);
  }
});

$('deleteButton').addEventListener('click', () => confirmDelete('글을 지울까요?', async () => {
  await posts.remove(post.id);
  location.replace('/community.html?deleted=1');
}));

$('reportButton').addEventListener('click', () => openReport({ targetType: 'POST', targetId: post.id, label: '이 글' }, $('reportButton')));

$('commentText').addEventListener('input', () => { $('commentCount').textContent = String($('commentText').value.length); });
$('commentForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const text = $('commentText').value.trim();
  if (!text) { $('commentError').textContent = '댓글을 써 주세요.'; $('commentText').focus(); return; }
  $('commentError').textContent = '';
  $('commentButton').disabled = true;
  try {
    render(await posts.comment(post.id, text));
    $('commentText').value = '';
    $('commentCount').textContent = '0';
    toast('댓글을 남겼어요.');
    $('commentList').lastElementChild?.scrollIntoView({ block: 'nearest' });
  } catch (err) {
    $('commentError').textContent = err.details?.length ? err.details.join(' ') : err.message;
    $('commentText').focus();
  } finally {
    $('commentButton').disabled = false;
  }
});

async function init() {
  mountAccount($('account'), { next: location.pathname + location.search });
  user = await auth.init();
  if (!id || !/^\d+$/.test(id)) {
    $('loadStatus').textContent = '글 주소가 올바르지 않아요.';
    return;
  }
  try {
    render(await posts.get(id));
    $('loadStatus').textContent = '';
    if (new URLSearchParams(location.search).get('saved') === '1') {
      history.replaceState(null, '', `/post.html?id=${id}`);
      toast('글을 올렸어요.');
    }
  } catch (e) {
    $('loadStatus').textContent = e.message;
  }
}

init();
