/* 해보양: same-origin backend demo. No sample response is treated as server data. */
(() => {
  "use strict";
  const API = "/api/v1";
  const TOKEN_KEY = "ganzi-demo-access-token";
  const state = {
    token: sessionStorage.getItem(TOKEN_KEY) || "",
    me: null, tasks: [], current: null, suggestedTask: null, studyReceivedAt: 0,
    detail: null, selectedTaskId: null, page: "home", sheet: "",
    accountTab: "register", busy: false, loaded: false, cameraStream: null, log: [],
    reminder: null, lastEndedSessionId: null, usage: null,
    chat: {}, chatScope: "home", chatCharacters: ["JOY"], chatPending: null, chatNotice: ""
  };
  const screen = document.getElementById("screen");
  const sheetRoot = document.getElementById("sheet-root");
  const logRoot = document.getElementById("request-log");
  const mini = document.getElementById("account-mini");
  const connection = document.getElementById("connection");
  const toastNode = document.getElementById("toast");
  let toastTimer;

  function el(tag, className, value) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (value !== undefined && value !== null) node.textContent = String(value);
    return node;
  }
  function add(parent, ...children) {
    for (const child of children) if (child) parent.append(child);
    return parent;
  }
  function button(label, action, className = "ghost-button", extra = {}) {
    const node = el("button", className, label);
    node.type = "button";
    node.dataset.action = action;
    for (const [key, value] of Object.entries(extra)) node.dataset[key] = String(value);
    node.disabled = state.busy;
    return node;
  }
  function input(name, label, opts = {}) {
    const wrap = el("label", "field");
    add(wrap, el("span", "", label));
    const node = document.createElement(opts.multiline ? "textarea" : "input");
    node.name = name;
    if (!opts.multiline) node.type = opts.type || "text";
    node.value = opts.value ?? "";
    node.placeholder = opts.placeholder || "";
    if (opts.required) node.required = true;
    if (opts.minLength) node.minLength = opts.minLength;
    if (opts.maxLength) node.maxLength = opts.maxLength;
    if (opts.autocomplete) node.autocomplete = opts.autocomplete;
    add(wrap, node);
    return wrap;
  }
  function notice(message, tone = "") { return el("div", "notice " + tone, message); }
  function h2(title, subtitle) {
    const wrap = el("div", "screen-head");
    const box = el("div");
    add(box, el("h2", "", title));
    if (subtitle) add(box, el("p", "sub", subtitle));
    add(wrap, box);
    return wrap;
  }
  function nav(active) {
    const bar = el("nav", "nav");
    for (const [page, icon, label] of [["home","⌂","홈"],["tasks","▤","할 일"],["room","◷","공부"],["account","♡","계정"]]) {
      const node = button("", "nav", page === active ? "active" : "", {page});
      add(node, el("span", "", icon), document.createTextNode(label));
      add(bar, node);
    }
    return bar;
  }
  function setPage(page) {
    if (page !== "room") stopCamera();
    state.page = page; state.sheet = ""; render();
  }
  function showToast(message) {
    toastNode.textContent = message;
    toastNode.classList.add("show");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toastNode.classList.remove("show"), 4000);
  }
  function protectedValue(value) {
    if (Array.isArray(value)) return value.map(protectedValue);
    if (value && typeof value === "object") {
      const safe = {};
      for (const [key, item] of Object.entries(value)) {
        safe[key] = /token|password|secret|authorization|content/i.test(key) ? "[가림]" : protectedValue(item);
      }
      return safe;
    }
    return value;
  }
  function logRequest(method, path, status, request, response) {
    state.log.unshift({method, path, status, request: protectedValue(request), response: protectedValue(response)});
    state.log = state.log.slice(0, 8);
    renderLog();
  }
  function renderLog() {
    logRoot.replaceChildren();
    if (!state.log.length) {
      add(logRoot, el("li", "muted", "아직 API 요청이 없습니다."));
      return;
    }
    for (const item of state.log) {
      const li = el("li");
      add(li, el("span", "method", item.method), el("code", "", item.path),
        el("span", "status " + (item.status >= 400 || item.status === 0 ? "fail" : ""), item.status || "실패"));
      const details = el("details");
      add(details, el("summary", "", "요청·응답 보기"),
        el("pre", "", JSON.stringify({request:item.request,response:item.response},null,2)));
      add(li, details); add(logRoot, li);
    }
  }
  async function request(method, path, body, useToken = true) {
    const headers = {Accept: "application/json"};
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (useToken && state.token) headers.Authorization = "Bearer " + state.token;
    let response, payload;
    try {
      response = await fetch(API + path, {
        method, headers, cache: "no-store",
        body: body === undefined ? undefined : JSON.stringify(body)
      });
      const raw = await response.text();
      try { payload = raw ? JSON.parse(raw) : null; }
      catch { payload = {message:"서버 응답을 읽을 수 없습니다."}; }
      logRequest(method, path, response.status, body ?? null, payload);
      if (!response.ok) {
        if (response.status === 401 && useToken &&
            (payload?.code === "AUTH_REQUIRED" || payload?.code === "AUTH_EXPIRED")) {
          clearIdentity();
          state.page = "welcome";
          render();
        }
        const retry = response.headers.get("Retry-After");
        const message = response.status === 429
          ? "요청이 많습니다." + (retry ? " " + retry + "초 후 다시 시도하세요." : " 잠시 후 다시 시도하세요.")
          : (payload && (payload.message || payload.code)) || "요청을 완료하지 못했습니다.";
        const error = new Error(message);
        error.status = response.status;
        error.code = payload && payload.code;
        throw error;
      }
      connection.textContent = "API 연결됨";
      connection.className = "connection ok";
      return payload;
    } catch (error) {
      if (!response) {
        connection.textContent = "API 연결 실패";
        connection.className = "connection error";
        logRequest(method, path, 0, body ?? null, {message:"네트워크 연결 실패"});
      }
      throw error;
    }
  }
  function setBusyControls(disabled) {
    for (const control of document.querySelectorAll("button, input[type=checkbox]")) {
      control.disabled = disabled;
    }
  }
  async function run(operation, success, preserveForm = false) {
    if (state.busy) return;
    const startPage = state.page, startSheet = state.sheet, startToken = state.token;
    state.busy = true;
    setBusyControls(true);
    let failed = false;
    try {
      await operation();
      if (success) showToast(success);
    } catch (error) {
      failed = true;
      showToast(error.message || "요청이 실패했습니다.");
    } finally {
      state.busy = false;
      if (failed && preserveForm && state.page === startPage &&
          state.sheet === startSheet && state.token === startToken) {
        setBusyControls(false);
      } else {
        render();
      }
    }
  }
  function clearIdentity() {
    sessionStorage.removeItem(TOKEN_KEY);
    state.token = ""; state.me = null; state.tasks = []; state.current = null; state.suggestedTask = null;
    state.detail = null; state.selectedTaskId = null; state.sheet = "";
    state.reminder = null; state.lastEndedSessionId = null; state.usage = null;
    state.chat = {}; state.chatPending = null; state.chatNotice = "";
    state.loaded = false;
    state.log = [];
    stopCamera();
  }
  function acceptSession(dto) {
    if (!dto || !dto.accessToken) throw new Error("인증 응답에 토큰이 없습니다.");
    if (state.me?.userId && state.me.userId !== dto.userId) state.log = [];
    state.token = dto.accessToken;
    state.loaded = false;
    sessionStorage.setItem(TOKEN_KEY, dto.accessToken);
    state.me = {userId:dto.userId, expiresAt:dto.expiresAt,
      accountType:dto.accountType || "GUEST", email:dto.email || null,
      emailVerified:!!dto.emailVerified};
  }
  async function refreshTasks() {
    const result = await request("GET", "/tasks");
    state.tasks = Array.isArray(result?.items) ? result.items : [];
  }
  async function refreshCurrent() {
    const result = await request("GET", "/study-sessions/current");
    state.current = result?.studySession || null;
    state.studyReceivedAt = Date.now();
    if (state.current?.taskId) {
      state.detail = await request("GET", "/tasks/" + encodeURIComponent(state.current.taskId));
    }
  }
  async function refreshAll() {
    state.me = await request("GET", "/me");
    await refreshTasks();
    await refreshCurrent();
    await refreshReminder();
    state.loaded = true;
  }
  async function refreshReminder() {
    const dto = await request("GET", "/start-reminders/current");
    state.reminder = dto?.reminder || null;
  }
  const characterNames = {JOY:"기쁨이", ANGRY:"화남이", SAD:"슬픔이"};
  const failureNames = {
    AI_CREDIT_EXHAUSTED:"API 크레딧이 소진됐어요.",
    AI_UPSTREAM_QUOTA_EXCEEDED:"AI 이용 한도에 도달했어요.",
    AI_BUDGET_EXCEEDED:"이번 기간의 대화 예산에 도달했어요.",
    AI_DAILY_USAGE_EXCEEDED:"오늘의 대화 한도에 도달했어요.",
    CHAT_DEADLINE_EXCEEDED:"답변 시간이 지나 대화를 마치지 못했어요.",
    STUDY_SESSION_ENDED:"공부가 끝나 답변을 표시하지 못했어요."
  };
  function chatScope() { return state.page==="room" && state.current ? "room:" + state.current.id : "home"; }
  function chatItems(scope) { return state.chat[scope] ||= []; }
  function latestChatId(scope) {
    const items = chatItems(scope);
    const last=[...items].reverse().find(item=>item.exchange);
    return last?.exchange.status === "SUCCEEDED" ? last.body.clientRequestId : null;
  }
  function reminderTime(value) {
    return new Intl.DateTimeFormat("ko-KR", {dateStyle:"medium", timeStyle:"short"}).format(new Date(value));
  }
  function localDateValue(date) {
    const d = new Date(date.getTime() - date.getTimezoneOffset() * 60000);
    return d.toISOString().slice(0,16);
  }
  function timezone() { return Intl.DateTimeFormat().resolvedOptions().timeZone || "Asia/Seoul"; }
  function taskById(id) { return state.tasks.find(item => item.id === id); }
  function sceneSheep(color, middle = false) {
    const sheep = el("div", "sheep " + color + (middle ? " middle" : ""));
    add(sheep, el("div", "ear"), el("div", "ear right"), el("div", "wool"),
      el("div", "face"), el("span", "mark", color === "red" ? "♥" : color === "blue" ? "✦" : "★"));
    sheep.setAttribute("aria-hidden","true");
    return sheep;
  }
  function home() {
    const box = el("div");
    const pad = el("div", "screen-pad");
    const top = h2("해보양", "오늘도, 함께하면 할 수 있어요. ♡");
    add(top, button("계정", "nav", "icon-button", {page:"account"}));
    add(pad, top);
    const scene = el("div", "hero-scene");
    const row = el("div", "sheep-row");
    add(row, sceneSheep("red"), sceneSheep("gold",true), sceneSheep("blue"));
    add(scene, el("div", "scene-table"), row);
    add(pad, scene);
    add(pad, button("💬 친구에게 이야기하기", "open-chat", "soft-button full"));
    if (state.reminder) add(pad, notice("다음 시작 약속 · " + reminderTime(state.reminder.remindAt), "blue"),
      button("다음 시작 시간 바꾸기", "open-reminder", "ghost-button full"));
    const title = el("div", "screen-head");
    add(title, el("h3", "section-title", "오늘의 할 일"), button("＋", "new-task", "icon-button"));
    add(pad, title);
    if (state.tasks.length) {
      for (const task of state.tasks.slice(0,3)) add(pad, taskCard(task));
    } else add(pad, emptyTasks());
    if (state.current) {
      add(pad, notice("진행 중인 공부가 있습니다. 서버의 현재 세션으로 돌아갑니다.", "blue"),
        button("진행 중인 공부로", "nav", "primary-button full", {page:"room"}));
    } else add(pad, button("▶ 함께 시작하기", "choose", "primary-button full"));
    add(pad, el("div", "spacer"),
      button("예시 할 일 추가 · 실제 서버에 저장", "example-task", "soft-button full"));
    add(box, pad, nav("home"));
    return box;
  }
  function emptyTasks() {
    const e = el("div", "empty");
    add(e, el("strong", "", "아직 할 일이 없어요"), document.createTextNode("직접 만들거나 할 일 없이 바로 공부를 시작할 수 있어요."));
    return e;
  }
  function taskCard(task, includeCheck = true) {
    const card = el("div", "task-card" + (task.id === state.selectedTaskId ? " active" : ""));
    if (includeCheck) {
      const check = document.createElement("input");
      check.type = "checkbox"; check.className = "round-check"; check.checked = task.status === "DONE";
      check.dataset.action = "toggle-task"; check.dataset.id = task.id;
      check.disabled = state.busy;
      check.setAttribute("aria-label", task.title + " 완료 상태");
      add(card, check);
    }
    const main = el("div", "task-main");
    add(main, el("strong", "", task.title),
      el("small", "", (task.subtaskSummary?.total || 0) + "개 세부 할 일 중 " +
        (task.subtaskSummary?.done || 0) + "개 완료" + (task.dueDate ? " · " + task.dueDate : "")));
    add(card, main, button("보기", "detail", "icon-button", {id:task.id}));
    return card;
  }
  function tasks() {
    const box = el("div"), pad = el("div","screen-pad");
    const top = h2("할 일", "나의 할 일을 서버에서 불러왔어요.");
    add(top, button("＋", "new-task", "icon-button"));
    add(pad, top);
    if (state.tasks.length) {
      add(pad, el("div","status-strip",state.tasks.filter(t=>t.status==="TODO").length+"개 남음 · "+state.tasks.filter(t=>t.status==="DONE").length+"개 완료"));
      for (const task of state.tasks) add(pad, taskCard(task));
    } else add(pad, emptyTasks());
    add(pad, el("div","spacer"), button("예시 할 일 추가 · 실제 서버에 저장","example-task","soft-button full"));
    add(box,pad,nav("tasks")); return box;
  }
  function detail() {
    const task = state.detail;
    if (!task) return tasks();
    const box = el("div"), pad = el("div","screen-pad");
    const top = el("div","detail-head");
    add(top,button("←","nav","icon-button",{page:"tasks"}),el("h2","",task.title));
    add(pad,top,el("p","detail-summary",(task.status==="DONE"?"완료":"진행 전")+" · 세부 할 일 "+(task.subtaskSummary?.done||0)+"/"+(task.subtaskSummary?.total||0)));
    const badges = el("div","badge-row");
    add(badges,el("span","badge",task.status),el("span","badge blue",task.dueDate||"기한 없음"));
    add(pad,badges);
    if (task.description) add(pad,notice(task.description));
    add(pad,el("h3","section-title","세부 할 일"));
    if (task.subtasks?.length) {
      for (const sub of task.subtasks) {
        const line = el("div","task-card");
        const check = document.createElement("input");
        check.type="checkbox";check.className="round-check";check.checked=sub.status==="DONE";
        check.dataset.action="toggle-subtask";check.dataset.id=sub.id;check.dataset.taskId=task.id;
        check.disabled=state.busy;check.setAttribute("aria-label",sub.title+" 완료 상태");
        add(line,check,el("div","task-main",sub.title),
          button("수정","edit-subtask","icon-button",{id:sub.id}),
          button("삭제","delete-subtask","icon-button",{id:sub.id}));
        add(pad,line);
      }
    } else add(pad,el("div","empty","세부 할 일이 아직 없어요."));
    const form = el("form");
    form.dataset.form="create-subtask";
    add(form,input("title","세부 할 일 추가",{required:true,maxLength:120,placeholder:"작은 단계 적기"}),
      submit("추가","soft-button full"));
    add(pad,form,el("hr","divider"));
    const controls=el("div","split");
    add(controls,button("할 일 수정","edit-task","ghost-button"),button("할 일 삭제","delete-task","danger-button"));
    add(pad,controls,el("div","spacer"));
    if (!state.current && task.status==="TODO") add(pad,button("이 할 일로 함께 시작","start-from-detail","primary-button full"));
    add(box,pad,nav("tasks"));return box;
  }
  function submit(label, className) {
    const b = el("button",className,label);
    b.type="submit";b.disabled=state.busy;return b;
  }
  function choose() {
    const box=el("div"),pad=el("div","screen-pad");
    add(pad,h2("뭐부터 같이 할까?","마음이 가는 할 일을 선택하고 함께 시작해요. ♡"),
      notice("할 일을 선택하지 않아도 공부를 시작할 수 있어요.","blue"));
    const available=state.tasks.filter(task=>task.status==="TODO");
    if (state.suggestedTask && state.suggestedTask.taskId === state.selectedTaskId) {
      const seconds = state.suggestedTask.completedStudySeconds;
      add(pad, notice("이전에 공부한 할 일을 골랐어요. 누적 공부 시간 " +
        Math.floor(seconds / 60) + "분 " + seconds % 60 + "초", "blue"));
    }
    if (!available.length) add(pad,emptyTasks());
    else for (const task of available) {
      const card=el("div","choice-card"+(state.selectedTaskId===task.id?" active":""));
      const check=el("span","round-check"+(state.selectedTaskId===task.id?" selected":""));
      add(card,check,el("div","task-main",task.title),
        button("선택","select-task","soft-button",{id:task.id}));
      add(pad,card);
    }
    add(pad,el("div","spacer"));
    if (state.selectedTaskId) add(pad,button("▶ 이 할 일로 시작하기","start-selected","primary-button full"));
    add(pad,button("선택 없이 시작하기","start-none","ghost-button full"));
    add(pad,el("div","spacer"),button("← 홈으로","nav","soft-button full",{page:"home"}));
    add(box,pad,nav("home"));return box;
  }
  function elapsedText() {
    const s=state.current;
    if (!s) return "00:00";
    let seconds=Number(s.studySeconds)||0;
    if (s.status==="STUDYING" && s.runningStartedAt && s.serverTime) {
      seconds+=Math.max(0,Math.floor((Date.now()-state.studyReceivedAt)/1000));
    }
    const min=Math.floor(seconds/60),sec=Math.floor(seconds%60);
    return String(min).padStart(2,"0")+":"+String(sec).padStart(2,"0");
  }
  function room() {
    if (!state.current) {
      const box=el("div"),pad=el("div","screen-pad");
      add(pad,h2("스터디룸","지금 진행 중인 공부가 없어요."),notice("서버의 현재 공부 세션을 확인했습니다."),
        button("함께 시작하기","choose","primary-button full"));
      add(box,pad,nav("room"));return box;
    }
    const box=el("div");
    const hero=el("div","room-hero"),bar=el("div","session-bar");
    const title=state.current.taskId ? (taskById(state.current.taskId)?.title||state.detail?.title||"선택한 할 일") : "할 일 없이 함께 공부";
    const labels=el("div");
    add(labels,el("strong","",title),el("div","small muted",state.current.status==="PAUSED"?"잠시 쉬는 중":"함께 집중하는 중"));
    add(bar,labels,el("span","timer",elapsedText()));
    add(hero,bar);
    const grid=el("div","room-grid");
    for (const [color,name] of [["red","화남이"],["blue","슬픔이"],["gold","기쁨이"]]) {
      const cell=el("div","room-cell");
      add(cell,sceneSheep(color),el("span","nameplate","● "+name));add(grid,cell);
    }
    const camera=el("div","room-cell camera-cell");
    if (state.cameraStream) {
      const video=el("video");video.autoplay=true;video.muted=true;video.playsInline=true;
      add(camera,video);
    } else add(camera,el("div","", "▧"),el("div","", "내 카메라가 꺼져 있어요."));
    const cameraButtons=el("div","camera-actions");
    add(cameraButtons,button(state.cameraStream?"카메라 끄기":"카메라 켜기",state.cameraStream?"camera-off":"camera-on","ghost-button"));
    add(camera,cameraButtons);add(grid,camera);add(hero,grid);
    const actions=el("div","room-actions");
    add(actions,button("▤ 할 일","room-tasks"),button("☏ 대화","room-chat"),
      button(state.current.status==="PAUSED"?"▶ 다시 시작":"☕ 쉬기",state.current.status==="PAUSED"?"resume":"pause"),
      button("↪ 나가기","exit-sheet"));
    add(hero,actions);add(box,hero);
    const pad=el("div","screen-pad");
    add(pad,notice(state.current.status==="PAUSED"?"공부 시간이 멈췄어요. 다시 시작을 누르면 같은 세션이 이어집니다.":"서버 시각과 누적 공부 시간을 기준으로 표시합니다.","blue"));
    if (state.current.taskId && state.detail?.subtasks?.length) {
      add(pad,el("h3","section-title","작은 할 일"));
      for (const sub of state.detail.subtasks) {
        const line=el("div","task-card");
        const check=document.createElement("input");check.type="checkbox";check.className="round-check";
        check.checked=sub.status==="DONE";check.disabled=state.busy;
        check.dataset.action="toggle-subtask";check.dataset.id=sub.id;check.dataset.taskId=state.current.taskId;
        check.setAttribute("aria-label",sub.title+" 완료 상태");
        add(line,check,el("div","task-main",sub.title));add(pad,line);
      }
      add(pad,el("div","status-strip","세부 할 일 "+(state.detail.subtaskSummary?.done||0)+"/"+(state.detail.subtaskSummary?.total||0)+"개 완료"));
    } else if (!state.current.taskId) add(pad,notice("할 일을 고르지 않고 시작했어요. 공부 기록은 이 세션에 남습니다."));
    add(box,pad,nav("room"));return box;
  }
  function welcome() {
    const box=el("div"),pad=el("div","screen-pad");
    add(pad,h2("반가워요","오늘도 천천히, 함께 시작해요."));
    const scene=el("div","hero-scene"),row=el("div","sheep-row");
    add(row,sceneSheep("red"),sceneSheep("gold",true),sceneSheep("blue"));
    add(scene,el("div","scene-table"),row);add(pad,scene);
    add(pad,notice("데모용 게스트 계정은 버튼을 누를 때 실제 서버에서 생성됩니다. 기존 계정이 있다면 로그인하세요.","blue"),
      button("게스트로 시작하기","guest-start","primary-button full"),el("div","spacer"),
      button("이메일 계정으로 로그인·가입","nav","ghost-button full",{page:"account"}));
    if (state.log.some(item=>item.status===401)) add(pad,notice("인증이 만료되거나 유효하지 않습니다. 새 게스트는 명시적으로 다시 시작해야 생성됩니다.","warn"));
    add(box,pad);return box;
  }
  function account() {
    const box=el("div"),pad=el("div","screen-pad");
    add(pad,h2("내 계정","공부 기록은 현재 계정에만 연결됩니다."));
    if (state.me) {
      add(pad,notice((state.me.accountType==="EMAIL"?"이메일 회원":"게스트")+" · "+(state.me.email||"이메일 없음"),"blue"));
      if (state.me.accountType==="EMAIL") {
        add(pad,el("span","badge",state.me.emailVerified?"이메일 확인됨":"이메일 미확인"));
        const form=el("form");form.dataset.form="password";
        add(form,input("currentPassword","현재 비밀번호",{type:"password",required:true,autocomplete:"current-password"}),
          input("newPassword","새 비밀번호 · 15~128자",{type:"password",required:true,autocomplete:"new-password"}),
          submit("비밀번호 변경","soft-button full"));
        add(pad,el("h3","section-title","비밀번호 변경"),form,el("div","spacer"),
          button("로그아웃","logout","ghost-button full"));
      } else {
        add(pad,notice("이 게스트의 할 일과 공부 기록을 이메일 계정으로 이어가려면 아래에서 가입하세요. 가입 후 새 토큰으로 교체됩니다."));
        add(pad,accountForms());
      }
      add(pad, el("hr","divider"), button("대화 사용량 보기", "open-usage", "ghost-button full"));
      const accountAdvanced=el("details","account-advanced");
      add(accountAdvanced,el("summary","","계정 관리"),button("현재 계정과 데이터 전체 삭제","delete-me-sheet","danger-button full"));
      add(pad,accountAdvanced);
    } else {
      add(pad,notice("로그인은 기존 게스트 기록을 자동 병합하지 않습니다. 게스트 기록을 이어가려면 게스트 상태에서 가입하세요.","warn"),
        accountForms(),el("div","spacer"),button("게스트로 시작하기","guest-start","ghost-button full"));
    }
    add(box,pad,nav("account"));return box;
  }
  function accountForms() {
    const box=el("div"),tabs=el("div","account-tabs");
    for (const [tab,label] of [["register","회원가입"],["login","로그인"]]) add(tabs,button(label,"account-tab",state.accountTab===tab?"active":"",{tab}));
    add(box,tabs);
    const form=el("form");form.dataset.form=state.accountTab;
    add(form,input("email","이메일",{type:"email",required:true,autocomplete:"email",placeholder:"name@example.com"}),
      input("password","비밀번호"+(state.accountTab==="register"?" · 15~128자":""),{
        type:"password",required:true,
        autocomplete:state.accountTab==="register"?"new-password":"current-password"
      }),submit(state.accountTab==="register"?"가입하고 기록 이어가기":"로그인하기","primary-button full"));
    add(box,form);return box;
  }
  function sheet(title, description) {
    const scrim=el("div","sheet-scrim");scrim.dataset.action="close-sheet";
    const panel=el("div","sheet"),handle=el("div","sheet-handle");
    const head=el("div","sheet-heading"),heading=el("h3","",title);
    heading.id="sheet-title";
    add(head,heading,button("닫기","close-sheet","sheet-close"));
    add(panel,handle,head,el("p","",description));
    panel.setAttribute("role","dialog");
    panel.setAttribute("aria-modal","true");
    panel.setAttribute("aria-labelledby","sheet-title");
    add(sheetRoot,scrim,panel);
    return panel;
  }
  function renderSheet() {
    sheetRoot.replaceChildren();
    if (!state.sheet) return;
    let panel;
    if (state.sheet==="new-task" || state.sheet==="edit-task") {
      const editing=state.sheet==="edit-task",task=editing?state.detail:null;
      panel=sheet(editing?"할 일 수정":"새 할 일","변경 사항은 저장 버튼을 누르면 서버에 반영됩니다.");
      const form=el("form");form.dataset.form=editing?"edit-task":"new-task";
      add(form,input("title","할 일 이름",{value:task?.title||"",required:true,maxLength:120,placeholder:"예: 머신러닝 보고서 작성"}),
        input("description","설명",{multiline:true,value:task?.description||"",placeholder:"필요하면 적어 주세요."}),
        input("dueDate","기한",{type:"date",value:task?.dueDate||""}),
        submit(editing?"수정 저장":"할 일 만들기","primary-button full"));
      add(panel,form);
    } else if (state.sheet==="edit-subtask") {
      const sub=state.detail?.subtasks?.find(x=>x.id===state.editSubtaskId);
      panel=sheet("세부 할 일 수정","이름을 바꾸면 서버의 세부 할 일에 반영됩니다.");
      const form=el("form");form.dataset.form="edit-subtask";
      add(form,input("title","세부 할 일",{value:sub?.title||"",required:true,maxLength:120}),
        submit("수정 저장","primary-button full"));add(panel,form);
    } else if (state.sheet==="delete-task" || state.sheet==="delete-subtask") {
      panel=sheet("정말 삭제할까요?","삭제한 항목은 이 화면에서 되돌릴 수 없습니다.");
      add(panel,button("삭제하기",state.sheet==="delete-task"?"confirm-delete-task":"confirm-delete-subtask","danger-button full"),
        el("div","spacer"),button("취소","close-sheet","ghost-button full"));
    } else if (state.sheet==="room-tasks") {
      panel=sheet("현재 할 일","스터디룸과 할 일 화면은 같은 서버 데이터를 사용합니다.");
      if (state.current?.taskId) {
        if (state.detail?.subtasks?.length) for (const sub of state.detail.subtasks) {
          const line=el("div","task-card");
          const check=document.createElement("input");
          check.type="checkbox";check.className="round-check";
          check.checked=sub.status==="DONE";check.disabled=state.busy;
          check.dataset.action="toggle-subtask";check.dataset.id=sub.id;
          check.dataset.taskId=state.current.taskId;
          check.setAttribute("aria-label",sub.title+" 완료 상태");
          add(line,check,el("div","task-main",sub.title));add(panel,line);
        }
        else add(panel,notice("세부 할 일이 없어요."));
      } else add(panel,notice("선택한 할 일이 없습니다."));
      add(panel,button("닫기","close-sheet","ghost-button full"));
    } else if (state.sheet==="room-chat") {
      panel=sheet("친구와 대화",state.chatScope.startsWith("room:") ? "함께 공부하면서 편하게 말해 보세요." : "오늘 마음은 어때요? 친구에게 이야기해 주세요.");
      const choices=el("div","character-choices");
      for (const [key,name] of Object.entries(characterNames)) {
        add(choices,button(name,"toggle-character","character-choice"+(state.chatCharacters.includes(key)?" selected":""),{key}));
      }
      add(panel,choices);
      const history=el("div","chat-history");
      const items=chatItems(state.chatScope);
      if (!items.length) add(history,notice("아직 대화가 없어요. 이야기하면 선택한 친구가 답해 줍니다.","blue"));
      for (const item of items) {
        add(history,el("div","chat-bubble mine",item.body.content));
        if (item.exchange?.status==="SUCCEEDED") for (const line of item.exchange.utterances||[]) {
          const reply=el("div","chat-bubble friend");
          add(reply,el("strong","",characterNames[line.characterKey]||line.characterKey),el("p","",line.content));
          add(history,reply);
        } else if (item.exchange?.status==="FAILED") add(history,notice(failureNames[item.exchange.failureCode]||"답변을 받지 못했어요. ("+(item.exchange.failureCode||"원인 미확인")+")","warn"));
        else if (state.chatPending?.body.clientRequestId===item.body.clientRequestId) add(history,notice("답변을 기다리고 있어요…","blue"));
      }
      add(panel,history);
      if (state.chatNotice) add(panel,notice(state.chatNotice,"warn"));
      if (state.chatPending && state.chatPending.uncertain) {
        add(panel,button("전송 결과 확인", "recover-chat", "ghost-button full"),
          button("같은 메시지 다시 전송", "retry-chat", "soft-button full"));
      }
      const form=el("form");form.dataset.form="chat";
      const field=input("content","내 이야기",{multiline:true,maxLength:1000,value:state.chatDraft||"",placeholder:"오늘 어떤 점이 어렵거나 기뻤나요?"});
      field.querySelector("textarea").disabled=!!state.chatPending;
      add(form,field,submit("친구에게 보내기","primary-button full"));
      if (state.chatPending) form.querySelector("button[type=submit]").disabled=true;
      add(panel,form);
    } else if (state.sheet==="usage") {
      panel=sheet("대화 사용량","이 계정의 사용 현황입니다. 금액은 앱의 추정치예요.");
      if (!state.usage) add(panel,notice("사용량을 불러오는 중이에요.","blue"));
      else {
        const day=state.usage.daily,month=state.usage.monthly;
        add(panel,el("div","usage-card","오늘(UTC) " + day.requests + "/"+state.usage.dailyRequestLimit+"회 · 예상 $"+day.estimatedUsd),
          el("div","usage-card","이번 달(UTC) "+month.requests+"회 · 예상 $"+month.estimatedUsd),
          notice(state.usage.availability==="AVAILABLE"?"현재 대화를 이용할 수 있어요.":(failureNames[state.usage.availability]||"현재 대화를 이용할 수 없어요. ("+state.usage.availability+")"),state.usage.availability==="AVAILABLE"?"blue":"warn"),
          notice("이 화면의 금액은 실제 청구서나 API 크레딧 잔액이 아닙니다."));
      }
      add(panel,button("새로 고침","open-usage","ghost-button full"));
    } else if (state.sheet==="exit") {
      panel=sheet("잠깐 나갈까?","취소하면 현재 공부 상태가 그대로 유지됩니다. 아래에서 확정할 때만 종료 API를 호출합니다.");
      add(panel,button("이따 다시 할게 · 공부 종료","exit-confirm","soft-button full",{reason:"USER_STOPPED"}),
        el("div","spacer"),button("오늘은 여기까지 · 공부 종료","exit-confirm","danger-button full",{reason:"USER_FINISHED"}),
        el("div","spacer"),button("계속 공부하기","close-sheet","ghost-button full"));
    } else if (state.sheet==="reminder") {
      panel=sheet("언제 다시 만날까?","공부가 끝났어요. 다음 시작 시간을 정해 둘 수 있어요.");
      add(panel,notice("이 화면은 예약 시간을 서버에 저장합니다. 휴대폰 잠금 화면 알림은 이 웹 시연에서 보내지 않습니다.","blue"));
      if (state.reminder) add(panel,el("div","usage-card","현재 약속 · "+reminderTime(state.reminder.remindAt)));
      for (const [label,minutes] of [["30분 뒤",30],["1시간 뒤",60],["3시간 뒤",180]]) {
        add(panel,button(label,"save-reminder","alert-row full",{minutes}));
      }
      const form=el("form");form.dataset.form="reminder";
      add(form,input("remindAt","직접 시간 고르기",{type:"datetime-local",required:true,value:localDateValue(new Date(Date.now()+3600000))}),
        submit("선택한 시간으로 예약","soft-button full"));
      add(panel,form);
      if (state.reminder) add(panel,button("약속 취소하기","cancel-reminder","danger-button full"));
      add(panel,button("홈으로 가기","finish-reminder","ghost-button full"));
    } else if (state.sheet==="delete-me") {
      panel=sheet("현재 계정 전체 삭제","이 계정의 할 일·세부 할 일·공부 기록·인증 정보가 삭제됩니다. 다른 게스트 계정은 대상이 아닙니다.");
      add(panel,notice("삭제 후 같은 계정으로 되돌릴 수 없습니다. 아래 버튼을 눌러 명시적으로 확정하세요.","warn"),
        button("현재 계정과 데이터 삭제","confirm-delete-me","danger-button full"),
        el("div","spacer"),button("취소","close-sheet","ghost-button full"));
    }
    if (panel) panel.setAttribute("role","dialog");
  }
  function renderMini() {
    if (!state.me) {mini.textContent="계정 연결 전 · 게스트로 시작하거나 로그인하세요.";return;}
    mini.replaceChildren();
    add(mini,el("strong","",state.me.accountType==="EMAIL"?"이메일 회원":"게스트"),
      el("div","",state.me.email||"이메일 없음"),
      el("div","",state.me.accountType==="EMAIL"&& !state.me.emailVerified?"이메일 확인 전":"현재 계정의 데이터만 표시"));
  }
  function render() {
    const page=!state.token && state.page!=="account"?"welcome":state.page;
    let content;
    if (state.token && !state.loaded) {
      content=loading();
    } else switch(page) {
      case "home":content=home();break;
      case "tasks":content=tasks();break;
      case "detail":content=detail();break;
      case "choose":content=choose();break;
      case "room":content=room();break;
      case "account":content=account();break;
      default:content=welcome();
    }
    screen.replaceChildren(content);
    renderSheet();renderMini();renderLog();
    const video=screen.querySelector(".camera-cell video");
    if (video && state.cameraStream) video.srcObject=state.cameraStream;
  }
  function loading() {
    const box=el("div"),pad=el("div","screen-pad");
    add(pad,h2("연결 중","서버에서 현재 계정과 할 일을 확인하고 있어요."),
      notice("실제 API 응답을 받은 뒤 내용이 표시됩니다.","blue"),
      button("다시 불러오기","retry-load","ghost-button full"));
    add(box,pad);
    return box;
  }
  function stopCamera() {
    if (state.cameraStream) for (const track of state.cameraStream.getTracks()) track.stop();
    state.cameraStream=null;
  }
  async function beginStudy(taskId) {
    const dto=await request("POST","/study-sessions",{
      clientRequestId:crypto.randomUUID(),taskId:taskId||null
    });
    state.current=dto;state.studyReceivedAt=Date.now();state.sheet="";state.page="room";
    state.reminder=null;state.lastEndedSessionId=null;
    state.detail=taskId?await request("GET","/tasks/"+encodeURIComponent(taskId)):null;
  }
  async function transition(action,reason) {
    if (!state.current) return;
    const body={action,expectedVersion:state.current.version};
    if (action==="END") body.endReason=reason;
    try {
      state.current=await request("PATCH","/study-sessions/"+encodeURIComponent(state.current.id),body);
      state.studyReceivedAt=Date.now();
    } catch(error) {
      if (error.status===409) {
        const latest=await request("GET","/study-sessions/"+encodeURIComponent(state.current.id));
        state.current=latest.status==="ENDED"?null:latest;
        state.studyReceivedAt=Date.now();
      }
      throw error;
    }
    if (action==="END") {
      state.lastEndedSessionId=state.current.id;
      state.current=null;state.detail=null;state.sheet="reminder";
      stopCamera();
    }
  }
  function chatError(error) {
    return failureNames[error.code] || error.message || "답변을 받지 못했어요.";
  }
  async function sendChat(pending) {
    pending.uncertain=false;state.chatPending=pending;state.chatNotice="";render();
    try {
      const result=await request("POST","/chat/exchanges",pending.body);
      const item=chatItems(pending.scope).find(x=>x.body.clientRequestId===pending.body.clientRequestId);
      if(item) item.exchange=result;
      state.chatPending=null;
      if (result.status==="FAILED") state.chatNotice=failureNames[result.failureCode]||"답변이 실패했어요. ("+(result.failureCode||"원인 미확인")+")";
    } catch(error) {
      if(error.code==="REQUEST_IN_PROGRESS" || error.status>=500) {
        pending.uncertain=true;
        state.chatNotice="전송 결과가 아직 확실하지 않아요. 먼저 결과를 확인해 주세요.";
      } else if(error.status) {
        chatItems(pending.scope).splice(chatItems(pending.scope).findIndex(x=>x.body.clientRequestId===pending.body.clientRequestId),1);
        state.chatDraft=pending.body.content;
        state.chatPending=null;
        state.chatNotice=chatError(error);
      } else {
        pending.uncertain=true;
        state.chatNotice="연결이 끊겨 전송 결과를 확인하지 못했어요. 먼저 결과를 확인해 주세요.";
      }
    } finally { render(); }
  }
  async function recoverChat() {
    const pending=state.chatPending;
    if(!pending) return;
    try {
      const result=await request("GET","/chat/exchanges?clientRequestId="+encodeURIComponent(pending.body.clientRequestId));
      const exchange=result?.items?.[0];
      if(exchange && exchange.status!=="PROCESSING") {
        const item=chatItems(pending.scope).find(x=>x.body.clientRequestId===pending.body.clientRequestId);
        if(item) item.exchange=exchange;
        state.chatPending=null;state.chatNotice=exchange.status==="FAILED"?(failureNames[exchange.failureCode]||"답변을 받지 못했어요."):"전송 결과를 확인했어요.";
      } else state.chatNotice=exchange?"친구가 아직 답을 생각하고 있어요. 잠시 뒤 다시 확인해 주세요.":"아직 확인된 결과가 없어요. 같은 메시지를 다시 전송할 수 있어요.";
    } catch(error) {state.chatNotice=chatError(error);}
    render();
  }
  async function saveReminder(date) {
    if (Number.isNaN(date.getTime()) || date.getTime()<=Date.now()+5000 || date.getTime()>Date.now()+7*86400000) throw new Error("지금부터 7일 이내의 미래 시간을 골라 주세요.");
    const remindAt=date.toISOString(), timeZone=timezone();
    let result;
    if(state.reminder) result=await request("PATCH","/start-reminders/"+encodeURIComponent(state.reminder.id),{
      expectedRemindAt:state.reminder.remindAt,expectedTimeZone:state.reminder.timeZone,remindAt,timeZone
    });
    else {
      if(!state.lastEndedSessionId) throw new Error("공부를 마친 뒤 다음 시작 시간을 정할 수 있어요.");
      result=await request("PUT","/study-sessions/"+encodeURIComponent(state.lastEndedSessionId)+"/next-start-reminder",{remindAt,timeZone});
    }
    state.reminder=result?.reminder||null;
    state.sheet="";state.page="home";
  }
  async function handleAction(action,data) {
    if (state.busy) return;
    switch(action) {
      case "nav":
        if (!state.token && data.page!=="account") {setPage("welcome");break;}
        setPage(data.page);
        if (data.page==="tasks"&&state.token) run(refreshTasks);
        if (data.page==="room"&&state.token) run(refreshCurrent);
        break;
      case "guest-start":run(async()=>{acceptSession(await request("POST","/guest-sessions",undefined,false));await refreshAll();state.page="home";},"게스트 계정이 준비됐어요.");break;
      case "retry-load":run(async()=>{await refreshAll();state.page=state.current?"room":"home";});break;
      case "choose":run(async()=>{
        const context = await request("GET", "/study-sessions/start-context");
        state.current = context.currentSession;
        state.studyReceivedAt = Date.now();
        state.suggestedTask = context.suggestedTask;
        state.selectedTaskId = context.suggestedTask?.taskId ?? null;
        state.detail = state.current?.taskId
          ? await request("GET", "/tasks/" + encodeURIComponent(state.current.taskId)) : null;
        if (!state.current) await refreshTasks();
        state.page = state.current ? "room" : "choose";
      });break;
      case "select-task":state.selectedTaskId=data.id;render();break;
      case "start-from-detail":state.suggestedTask=null;state.selectedTaskId=state.detail?.id;setPage("choose");break;
      case "start-selected":run(()=>beginStudy(state.selectedTaskId),"함께 공부를 시작했어요.");break;
      case "start-none":run(()=>beginStudy(null),"할 일 없이 공부를 시작했어요.");break;
      case "detail":run(async()=>{state.detail=await request("GET","/tasks/"+encodeURIComponent(data.id));state.page="detail";});break;
      case "new-task":case "edit-task":case "edit-subtask":case "delete-task":case "delete-subtask":
        state.sheet=action;if(data.id)state.editSubtaskId=data.id;render();break;
      case "delete-me-sheet":state.sheet="delete-me";render();break;
      case "close-sheet":state.sheet="";render();break;
      case "toggle-task":run(async()=>{const t=taskById(data.id);if(!t)return;await request("PATCH","/tasks/"+encodeURIComponent(t.id),{status:t.status==="DONE"?"TODO":"DONE"});await refreshTasks();if(state.detail?.id===t.id)state.detail=await request("GET","/tasks/"+encodeURIComponent(t.id));},"할 일 상태를 저장했어요.");break;
      case "toggle-subtask":run(async()=>{const t=state.detail;if(!t)return;const sub=t.subtasks.find(x=>x.id===data.id);if(!sub)return;await request("PATCH","/tasks/"+encodeURIComponent(data.taskId)+"/subtasks/"+encodeURIComponent(data.id),{status:sub.status==="DONE"?"TODO":"DONE"});state.detail=await request("GET","/tasks/"+encodeURIComponent(data.taskId));await refreshTasks();},"세부 할 일 상태를 저장했어요.");break;
      case "confirm-delete-task":run(async()=>{await request("DELETE","/tasks/"+encodeURIComponent(state.detail.id));state.detail=null;state.sheet="";state.page="tasks";await refreshTasks();},"할 일을 삭제했어요.");break;
      case "confirm-delete-subtask":run(async()=>{await request("DELETE","/tasks/"+encodeURIComponent(state.detail.id)+"/subtasks/"+encodeURIComponent(state.editSubtaskId));state.detail=await request("GET","/tasks/"+encodeURIComponent(state.detail.id));state.sheet="";await refreshTasks();},"세부 할 일을 삭제했어요.");break;
      case "example-task":run(async()=>{
        const task=await request("POST","/tasks",{title:"머신러닝 보고서 작성",description:"백엔드 연동 시연용으로 직접 추가한 할 일"});
        try {
          await request("POST","/tasks/"+encodeURIComponent(task.id)+"/subtasks",{title:"자료 정리하기"});
          await request("POST","/tasks/"+encodeURIComponent(task.id)+"/subtasks",{title:"첫 문단 작성하기"});
        } finally {await refreshTasks();}
      },"예시 할 일을 서버에 저장했어요.");break;
      case "pause":run(()=>transition("PAUSE"),"공부를 잠시 멈췄어요.");break;
      case "resume":run(()=>transition("RESUME"),"같은 공부를 다시 시작했어요.");break;
      case "exit-sheet":state.sheet="exit";render();break;
      case "exit-confirm":run(()=>transition("END",data.reason),"공부를 종료했어요.");break;
      case "finish-reminder":state.sheet="";state.page="home";run(refreshTasks);break;
      case "open-reminder":run(async()=>{await refreshReminder();state.sheet="reminder";});break;
      case "save-reminder":run(()=>saveReminder(new Date(Date.now()+Number(data.minutes)*60000)),"다음 시작 시간을 저장했어요.");break;
      case "cancel-reminder":run(async()=>{if(state.reminder){await request("DELETE","/start-reminders/"+encodeURIComponent(state.reminder.id));state.reminder=null;}state.sheet="";state.page="home";},"다음 시작 약속을 취소했어요.");break;
      case "open-usage":run(async()=>{state.usage=await request("GET","/chat/usage");state.sheet="usage";});break;
      case "open-chat":case "room-chat":state.chatScope=chatScope();state.chatNotice="";state.sheet="room-chat";render();break;
      case "toggle-character":{
        const list=state.chatCharacters, key=data.key;
        state.chatCharacters=list.includes(key)?(list.length>1?list.filter(x=>x!==key):list):[...list,key];
        render();break;
      }
      case "recover-chat":recoverChat();break;
      case "retry-chat":if(state.chatPending?.uncertain)sendChat(state.chatPending);break;
      case "room-tasks":state.sheet=action;render();break;
      case "camera-on":
        run(async()=>{if(!navigator.mediaDevices?.getUserMedia)throw new Error("이 환경에서는 카메라를 사용할 수 없습니다.");state.cameraStream=await navigator.mediaDevices.getUserMedia({video:true,audio:false});},"카메라를 켰어요. 영상은 서버로 전송하지 않습니다.");break;
      case "camera-off":stopCamera();render();showToast("카메라를 껐어요.");break;
      case "account-tab":state.accountTab=data.tab;render();break;
      case "logout":run(async()=>{await request("POST","/auth/logout");clearIdentity();state.page="welcome";},"로그아웃했어요.");break;
      case "confirm-delete-me":run(async()=>{await request("DELETE","/me");clearIdentity();state.page="welcome";},"현재 계정과 데이터를 삭제했어요.");break;
    }
  }
  document.addEventListener("click",event=>{
    const target=event.target.closest("[data-action]");
    if (!target)return;
    if (target.matches("input[type=checkbox]"))return;
    event.preventDefault();
    handleAction(target.dataset.action,target.dataset);
  });
  document.addEventListener("change",event=>{
    const target=event.target;
    if (target.matches("input[type=checkbox][data-action]"))handleAction(target.dataset.action,target.dataset);
  });
  document.addEventListener("input",event=>{
    if(event.target.matches('form[data-form="chat"] textarea[name="content"]')) {
      state.chatDraft=event.target.value;
    }
  });
  document.addEventListener("keydown",event=>{
    if(event.key==="Escape" && state.sheet) {
      event.preventDefault();
      state.sheet="";
      render();
    }
  });
  document.addEventListener("submit",event=>{
    const form=event.target.closest("form[data-form]");
    if(!form)return;
    event.preventDefault();
    const fd=new FormData(form),kind=form.dataset.form;
    const value=name=>String(fd.get(name)||"");
    if(kind==="register"||kind==="login") {
      if(kind==="register" && (Array.from(value("password")).length<15 || Array.from(value("password")).length>128)) {
        showToast("비밀번호는 15~128자로 입력하세요.");return;
      }
      run(async()=>{
        const dto=await request("POST","/auth/"+kind,{email:value("email"),password:value("password")},kind==="register"&&!!state.token);
        form.querySelectorAll('input[type="password"]').forEach(input=>input.value="");
        acceptSession(dto);await refreshAll();state.page="home";
      },kind==="register"?"회원 계정으로 이어졌어요.":"로그인했어요.",true);
    } else if(kind==="password") {
      if(Array.from(value("newPassword")).length<15 || Array.from(value("newPassword")).length>128) {
        showToast("새 비밀번호는 15~128자로 입력하세요.");return;
      }
      run(async()=>{
        const dto=await request("POST","/auth/password",{currentPassword:value("currentPassword"),newPassword:value("newPassword")});
        form.querySelectorAll('input[type="password"]').forEach(input=>input.value="");
        acceptSession(dto);
        await refreshAll();
      },"비밀번호를 변경했어요.",true);
    } else if(kind==="chat") {
      const content=value("content").trim();
      if(!content || Array.from(content).length>1000 || state.chatPending) return;
      const scope=state.chatScope;
      const body={clientRequestId:crypto.randomUUID(),content,studySessionId:scope.startsWith("room:")?scope.slice(5):null,
        previousRequestId:latestChatId(scope),characterKeys:[...state.chatCharacters]};
      const pending={body,scope,uncertain:false};
      chatItems(scope).push({body,exchange:null});state.chatDraft="";
      sendChat(pending);
    } else if(kind==="reminder") {
      run(()=>saveReminder(new Date(value("remindAt"))),"다음 시작 시간을 저장했어요.",true);
    } else if(kind==="new-task") {
      run(async()=>{
        const body={title:value("title"),description:value("description")||null,dueDate:value("dueDate")||null};
        await request("POST","/tasks",body);state.sheet="";await refreshTasks();state.page="tasks";
      },"할 일을 만들었어요.",true);
    } else if(kind==="edit-task") {
      run(async()=>{
        const body={title:value("title"),description:value("description")||null,dueDate:value("dueDate")||null};
        state.detail=await request("PATCH","/tasks/"+encodeURIComponent(state.detail.id),body);
        state.detail=await request("GET","/tasks/"+encodeURIComponent(state.detail.id));
        state.sheet="";await refreshTasks();
      },"할 일을 수정했어요.",true);
    } else if(kind==="create-subtask") {
      run(async()=>{
        const id=state.detail.id;
        await request("POST","/tasks/"+encodeURIComponent(id)+"/subtasks",{title:value("title")});
        state.detail=await request("GET","/tasks/"+encodeURIComponent(id));await refreshTasks();
      },"세부 할 일을 추가했어요.",true);
    } else if(kind==="edit-subtask") {
      run(async()=>{
        const id=state.detail.id;
        await request("PATCH","/tasks/"+encodeURIComponent(id)+"/subtasks/"+encodeURIComponent(state.editSubtaskId),{title:value("title")});
        state.detail=await request("GET","/tasks/"+encodeURIComponent(id));state.sheet="";
      },"세부 할 일을 수정했어요.",true);
    }
  });
  window.addEventListener("pagehide",stopCamera);
  setInterval(()=>{
    const timer=screen.querySelector(".timer");
    if(timer&&state.current)timer.textContent=elapsedText();
  },1000);
  render();
  if(state.token) run(async()=>{await refreshAll();state.page=state.current?"room":"home";});
})();
