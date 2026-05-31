(function () {
  const state = {
    questions: [],
    index: 0,
    // per-question id -> { selected:Set<choiceId>, submitted:bool, correct:bool }
    answers: new Map(),
    user: null,
    // Set<questionId> — verifiers must vote 👍/👎 (with reason) on each
    // question before they're allowed to leave it. Populated by loadVoteStats
    // for any question they've already voted on, and by castVote on success.
    votedQuestionIds: new Set(),
    // When the user started this session — used to record duration on Finish
    // for the "My reports" dashboard.
    startedAt: null,
    // Once we've POSTed an attempt for this finalize, don't repeat (the user
    // can hit Back to test → Finish again and re-record on each finalize).
    lastRecordedFinishAt: null,
    // Exam-mode only: set when the user first clicks Finish. From that
    // point the action bar is reduced to just the header Finish button —
    // even if they cancel the confirm they can't go back to answering.
    finishInitiated: false,
  };

  // Per-exam, set from exam metadata in init().
  let examSlug = null;
  let practiceCount = 60;

  // Delivery mode for this session: "practice" (default) or "exam".
  //   practice — feedback after every submit, can navigate / skip / retest.
  //   exam     — no feedback until Finish, no skipping, no going back,
  //              answers locked once submitted. Real-test simulation.
  let testMode = "practice";
  function isExamMode() { return testMode === "exam"; }

  const detail = {
    filter: null, // "correct" | "incorrect"
    page: 0,
    pageSize: 3,
    items: [],
  };

  let pendingConfirm = null;
  let pendingAlt = null;
  let pendingCancel = null;
  let confirmReturnTo = "quiz"; // "quiz" | "results"

  let TOTAL_MS = 90 * 60 * 1000;   // exam duration, overridden from metadata
  const WARN_MS = 5 * 60 * 1000;   // amber when <= 5 min remain
  const timer = {
    storageKey: null,
    state: "not_started", // not_started | running | paused | expired
    endTime: 0,           // epoch ms — only valid when running
    remainingMs: TOTAL_MS,
    intervalId: null,
  };

  const $ = (sel) => document.querySelector(sel);

  function getExamSlug() {
    const params = new URLSearchParams(window.location.search);
    const s = params.get("exam");
    return s && s.trim() ? s.trim() : null;
  }

  /** Optional ?retake=<attemptId> on the URL — when present, the quiz loads
   *  the EXACT question set from that prior attempt instead of taking a
   *  fresh random sample from the bank. Lets a user practise the same 60
   *  questions over and over for spaced repetition. */
  function getRetakeAttemptId() {
    const params = new URLSearchParams(window.location.search);
    const s = params.get("retake");
    if (!s) return null;
    const n = parseInt(s, 10);
    return Number.isFinite(n) && n > 0 ? n : null;
  }

  async function init() {
    examSlug = getExamSlug();
    const retakeId = getRetakeAttemptId();
    // Mode: "exam" enables strict simulation; anything else is practice.
    const modeParam = (new URLSearchParams(window.location.search).get("mode") || "").toLowerCase();
    testMode = modeParam === "exam" ? "exam" : "practice";
    if (!examSlug && !retakeId) { window.location.href = "/exams.html"; return; }
    await loadCurrentUser();
    try {
      // Two load paths: ?retake=<id> pulls the exact question set saved
      // on that previous attempt; otherwise normal random sample for the
      // exam slug. Both return the same {exam, questions} payload shape.
      const url = retakeId
        ? "/api/test-attempts/" + retakeId + "/retake-questions"
        : "/api/exams/" + encodeURIComponent(examSlug) + "/questions";
      const res = await fetch(url, { credentials: "same-origin" });
      if (res.status === 401 || res.status === 403) { window.location.href = "/login"; return; }
      if (res.status === 404 || res.status === 410) {
        // Retake target is gone or not retakeable — fall back to a fresh
        // sample on the same exam if we know the slug, else send them to
        // the exams index.
        window.location.href = examSlug ? ("/?exam=" + encodeURIComponent(examSlug)) : "/exams.html";
        return;
      }
      if (!res.ok) throw new Error("Failed to load questions");
      const data = await res.json();
      const meta = data.exam || {};
      // For retakes we adopt the exam slug from the attempt's stored exam
      // so the timer / branding / submit endpoints all keep working.
      if (!examSlug && meta.slug) examSlug = meta.slug;
      practiceCount = meta.questionsPerSession && meta.questionsPerSession > 0 ? meta.questionsPerSession : 60;
      TOTAL_MS = (meta.durationMinutes && meta.durationMinutes > 0 ? meta.durationMinutes : 90) * 60 * 1000;
      PASSING_PCT = (meta.passingScorePercent && meta.passingScorePercent > 0) ? meta.passingScorePercent : 65;
      applyExamBranding(meta);
      const all = Array.isArray(data.questions) ? data.questions : [];
      if (retakeId) {
        // Retake: keep the exact order the attempt stored. The user can
        // still use the in-session Reset button to reshuffle, but the
        // initial replay preserves the original sequence.
        state.questions = all;
      } else {
        shuffleInPlace(all);
        state.questions = all.slice(0, practiceCount);
      }
      state.questions.forEach((q, i) => { q.number = i + 1; });
    } catch (e) {
      $("#qText").textContent = "Failed to load questions: " + e.message;
      // Drop the splash so the error text is visible instead of "Loading…".
      document.body.classList.remove("quiz-booting");
      return;
    }
    state.questions.forEach((q) => state.answers.set(q.id, { selected: new Set(), submitted: false, correct: false, visited: false }));
    state.startedAt = new Date();
    state.lastRecordedFinishAt = null;
    initSidebarWidth();
    renderSidebar();
    renderQuestion();
    bindActions();
    initResizer();
    initTimer();
    applyExamModeUiLockdown();
    // Reveal the now-populated UI. Everything above is synchronous so
    // by this point Q1 is fully painted into the DOM — the splash drops
    // and the real content is already in place, no second flash.
    document.body.classList.remove("quiz-booting");
  }

  /** Exam mode disables every control that would let a user pause, reset,
   *  or reshuffle the test mid-flight — the only allowed actions are
   *  answer the current question, advance to the next, or Finish.
   *  Hidden (not just disabled) so they don't even appear as options. */
  function applyExamModeUiLockdown() {
    if (!isExamMode()) return;
    const toHide = ["#timerToggle", "#timerReset", "#resetBtn"];
    toHide.forEach(function (sel) {
      const el = $(sel);
      if (el) el.style.display = "none";
    });
    // Collapse the question-list sidebar entirely — closer to a real
    // proctored exam where you can't see "I've answered N of M".
    const layout = document.getElementById("layout");
    if (layout) layout.classList.add("exam-mode");
    applyExamNavigationLock();
  }

  /** Block every navigation escape hatch while a real-exam run is in
   *  flight. Three layers:
   *   1. CSS class on <body> hides topbar nav links + the Sign-out form,
   *      leaving only the brand + user badge so the user has identity
   *      context but can't reach Exams / My reports / etc.
   *   2. Browser Back button trap — push a duplicate history entry on
   *      load, then re-push on every popstate so back is a no-op.
   *   3. beforeunload prompt — reload / close-tab / typed-URL all
   *      fire the browser's "Leave site?" confirmation.
   *
   *  All three are released in releaseExamNavigationLock() at finalize. */
  let examNavLockActive = false;
  function applyExamNavigationLock() {
    if (examNavLockActive) return;
    examNavLockActive = true;
    document.body.classList.add("exam-mode-active");

    // Browser Back: seed an extra history entry, then keep re-pushing
    // it whenever the user tries to leave so they stay on the page.
    try { history.pushState({ examLock: true }, "", location.href); } catch (e) { /* old browsers */ }
    window.addEventListener("popstate", onExamPopState);

    // Reload / close / nav-away — prompt before leaving.
    window.addEventListener("beforeunload", onExamBeforeUnload);
  }

  function releaseExamNavigationLock() {
    if (!examNavLockActive) return;
    examNavLockActive = false;
    document.body.classList.remove("exam-mode-active");
    window.removeEventListener("popstate", onExamPopState);
    window.removeEventListener("beforeunload", onExamBeforeUnload);
  }

  function onExamPopState() {
    // Always cancel the back — push a fresh history entry to stay put.
    if (!examNavLockActive) return;
    try { history.pushState({ examLock: true }, "", location.href); } catch (e) { /* no-op */ }
  }
  function onExamBeforeUnload(e) {
    if (!examNavLockActive) return undefined;
    // After Finish the attempt is already recorded — no point warning
    // about lost answers. We still keep the topbar visually locked so
    // exam-mode wraps cleanly, but tab-close / reload from the results
    // screen shouldn't trip the browser prompt.
    if (state.finished) return undefined;
    // Modern browsers ignore the message string and show their own
    // generic copy, but setting returnValue is still required to
    // trigger the dialog.
    e.preventDefault();
    e.returnValue = "Real exam in progress — leaving will lose your answers and won't record a finished attempt.";
    return e.returnValue;
  }

  function applyExamBranding(meta) {
    const name = meta && meta.name ? meta.name : "Practice Exam";
    const modeLabel = isExamMode() ? "Exam mode" : "Practice";
    const brand = document.querySelector(".brand");
    if (brand) brand.textContent = name + " — " + practiceCount + " Questions · " + modeLabel;
    document.title = name + " — " + modeLabel;
    // The header badge is hardcoded "Practice mode (Beta)" in the template.
    // Swap its text + class when the user is in a real-exam run so they
    // (and admins watching screenshots) never see "Practice" during exam.
    const badge = document.getElementById("modeBadge");
    if (badge) {
      if (isExamMode()) {
        badge.textContent = "Real exam mode";
        badge.classList.remove("badge-practice");
        badge.classList.add("badge-exam");
      } else {
        badge.textContent = "Practice mode (Beta)";
        badge.classList.remove("badge-exam");
        badge.classList.add("badge-practice");
      }
    }
    const progress = $("#progressText");
    if (progress) progress.textContent = "0/" + practiceCount;
    // The reset-timer tooltip is hardcoded "Reset timer to 90:00" in
    // index.html — overwrite it with the exam's actual duration so the
    // hover text is accurate per-cert.
    const resetBtn = document.getElementById("timerReset");
    if (resetBtn) resetBtn.setAttribute("title", "Reset timer to " + formatMs(TOTAL_MS));
  }

  async function loadCurrentUser() {
    try {
      const res = await fetch("/api/me", { credentials: "same-origin" });
      if (!res.ok) return;
      const data = await res.json();
      if (!data.authenticated) { window.location.href = "/login"; return; }
      state.user = data;
      const name = data.fullName && data.fullName.trim() ? data.fullName : data.email;
      const role = data.role || "USER";
      const badge = document.getElementById("userBadge");
      if (badge) badge.textContent = name + " (" + role + ")";
      // Back-compat: older pages may still have #userName around.
      const legacy = document.getElementById("userName");
      if (legacy) legacy.textContent = name;
      if (role === "ADMIN") {
        document.querySelectorAll(".admin-only").forEach(el => { el.style.display = ""; });
      }
      if (role === "SUPERADMIN") {
        document.querySelectorAll(".superadmin-only").forEach(el => { el.style.display = ""; });
      }
      if (role === "SUPERADMIN") {
        document.querySelectorAll(".user-link").forEach(el => { el.style.display = "none"; });
      }
      // Detect a server restart by comparing the bootId returned now against
      // the one we stored on the previous load. Mismatch ⇒ any persisted
      // timer state in localStorage is from a previous server lifetime and
      // should be wiped so the user starts fresh.
      handleBootIdChange(data.bootId);
    } catch (e) {
      // ignore — server may be down; user header just stays blank
    }
  }

  /** Server-restart detection. On every page load we compare the server's
   *  in-memory bootId against the last one we stored in localStorage. If
   *  they differ, any persisted timer state (sfquiz:timer:*) belongs to a
   *  previous server lifetime — wipe it so the user gets a fresh timer
   *  instead of resuming a stale countdown. Then store the new bootId. */
  const BOOT_ID_KEY = "sfquiz:server-boot-id";
  const TIMER_KEY_PREFIX = "sfquiz:timer:";
  function handleBootIdChange(bootId) {
    if (bootId == null) return;
    const stored = localStorage.getItem(BOOT_ID_KEY);
    const incoming = String(bootId);
    if (stored !== incoming) {
      let wiped = 0;
      for (let i = localStorage.length - 1; i >= 0; i--) {
        const key = localStorage.key(i);
        if (key && key.startsWith(TIMER_KEY_PREFIX)) {
          localStorage.removeItem(key);
          wiped++;
        }
      }
      localStorage.setItem(BOOT_ID_KEY, incoming);
      if (wiped > 0) {
        // Helpful breadcrumb in DevTools — quiet during normal navigation.
        try { console.info("Server restart detected — wiped " + wiped + " stale timer entrie(s)."); } catch (e) {}
      }
    }
  }

  function bindActions() {
    const voteUp = document.getElementById("voteUpBtn");
    const voteDn = document.getElementById("voteDownBtn");
    if (voteUp) voteUp.addEventListener("click", () => castVote(1));
    if (voteDn) voteDn.addEventListener("click", () => castVote(-1));
    $("#submitBtn").addEventListener("click", onSubmit);
    $("#backBtn").addEventListener("click", () => goTo(state.index - 1));
    $("#nextBtn").addEventListener("click", onNextClicked);
    $("#finishBtn").addEventListener("click", onFinish);
    $("#backToTestBtn").addEventListener("click", hideResultsPanel);
    // Exam-mode-only: explicit exit point on the results screen. Released
    // the nav lock + bounces the user to their attempt history.
    const examExit = document.getElementById("examExitBtn");
    if (examExit) {
      examExit.addEventListener("click", function () {
        releaseExamNavigationLock();
        const slug = examSlug ? "?exam=" + encodeURIComponent(examSlug) : "";
        window.location.href = "/my/reports/per-test" + slug;
      });
    }
    $("#retestBtn").addEventListener("click", onResetTest);
    $("#statCorrect").addEventListener("click", () => showResultsDetail("correct"));
    $("#statIncorrect").addEventListener("click", () => showResultsDetail("incorrect"));
    $("#backToSummaryBtn").addEventListener("click", hideResultsDetail);
    $("#resultsPrevBtn").addEventListener("click", () => changeDetailPage(-1));
    $("#resultsNextBtn").addEventListener("click", () => changeDetailPage(1));
    $("#confirmCancelBtn").addEventListener("click", onConfirmCancel);
    $("#confirmAltBtn").addEventListener("click", onConfirmAlt);
    $("#confirmOkBtn").addEventListener("click", onConfirmOk);
    $("#resetBtn").addEventListener("click", onResetTest);
    $("#timerToggle").addEventListener("click", onTimerToggle);
    $("#timerReset").addEventListener("click", onResetTimer);
    document.addEventListener("visibilitychange", () => { if (!document.hidden) renderTimer(); });
  }

  function renderSidebar() {
    const ul = $("#qlist");
    ul.innerHTML = "";
    state.questions.forEach((q, i) => {
      const li = document.createElement("li");
      li.dataset.index = i;
      li.classList.toggle("active", i === state.index);
      const ans = state.answers.get(q.id);
      let statusClass = "";
      let statusText = "";
      if (ans && ans.submitted) {
        if (isExamMode()) {
          // Exam mode hides correctness during the test — show a neutral
          // "Answered" tag instead so the user can still tell which
          // questions they've completed.
          statusClass = "answered";
          statusText = "Answered";
        } else {
          statusClass = ans.correct ? "correct" : "incorrect";
          statusText = ans.correct ? "Correct" : "Incorrect";
        }
      } else if (!isExamMode() && ans && ans.visited && i !== state.index) {
        // "Skipped" only applies in practice — exam mode doesn't let you
        // skip, so this branch never fires there.
        statusClass = "skipped";
        statusText = "Skipped";
      }
      li.innerHTML =
        '<span class="q-icon"></span>' +
        '<div class="q-meta">' +
          '<div class="q-head">' +
            '<span>Question ' + q.number + '</span>' +
            (statusText ? '<span class="q-status ' + statusClass + '">' + statusText + '</span>' : '') +
          '</div>' +
          '<div class="q-text">' + escapeHtml(truncate(q.text, 60)) + '</div>' +
        '</div>';
      li.addEventListener("click", () => goTo(i));
      ul.appendChild(li);
    });
  }

  function truncate(s, n) { return s.length > n ? s.slice(0, n - 1) + "…" : s; }
  function escapeHtml(s) {
    return s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  }

  function renderQuestion() {
    const q = state.questions[state.index];
    if (!q) return;
    const ans = state.answers.get(q.id);
    ans.visited = true;

    // Header counts
    const total = state.questions.length;
    const completed = countCompleted();
    $("#progressText").textContent = (state.index + 1) + "/" + total;
    $("#progressFill").style.width = ((completed / total) * 100).toFixed(1) + "%";

    // Question
    $("#qNum").textContent = "Question " + q.number + ":";
    $("#qText").textContent = q.text;
    $("#qHint").textContent = q.type === "MULTI" ? "Choose all that apply:" : "";

    // Choices
    const wrap = $("#choices");
    wrap.innerHTML = "";
    const inputType = q.type === "MULTI" ? "checkbox" : "radio";
    const inputName = "q-" + q.id;
    q.choices.forEach((c) => {
      const div = document.createElement("label");
      div.className = "choice";
      const checked = ans.selected.has(c.id) ? "checked" : "";
      const disabledAttr = ans.submitted ? "disabled" : "";
      div.innerHTML =
        '<input type="' + inputType + '" name="' + inputName + '" value="' + c.id + '" ' + checked + ' ' + disabledAttr + ' />' +
        '<div class="ctext"><span class="clabel">' + c.label + '.</span>' + escapeHtml(c.text) + '</div>';
      const input = div.querySelector("input");
      input.addEventListener("change", () => {
        if (q.type === "MULTI") {
          if (input.checked) ans.selected.add(c.id); else ans.selected.delete(c.id);
        } else {
          ans.selected.clear();
          ans.selected.add(c.id);
        }
      });
      wrap.appendChild(div);
    });

    // Banner / explanation / locked styling.
    // EXAM mode hides correctness, explanation and vote DURING the
    // live test. Once state.finished flips (user clicked Finish), the
    // test view becomes a review screen — feedback is revealed even
    // in exam mode so the user can learn from what they got wrong.
    const liveExam = isExamMode() && !state.finished;
    if (ans.submitted) {
      if (liveExam) {
        $("#resultBanner").classList.add("hidden");
        $("#explanationBox").classList.add("hidden");
        $("#voteBar").classList.add("hidden");
      } else {
        applyResultStyling(q, ans);
        showBanner(ans.correct);
        showExplanation(q, ans);
      }
      $("#submitBtn").disabled = true;
    } else {
      $("#resultBanner").classList.add("hidden");
      $("#explanationBox").classList.add("hidden");
      if (liveExam) $("#voteBar").classList.add("hidden");
      $("#submitBtn").disabled = timer.state === "expired";
    }

    // Nav buttons.
    // LIVE EXAM mode: Back disabled, Next hidden (Submit auto-advances),
    // Submit relabelled. POST-FINISH review (state.finished == true):
    // free Back/Next navigation, Submit hidden (everything's already
    // submitted). PRACTICE mode keeps its existing skip/back freedom.
    const nextBtn = $("#nextBtn");
    const submitBtn = $("#submitBtn");
    const isLast = state.index === total - 1;
    if (liveExam) {
      $("#backBtn").disabled = true;
      // Hide the Next button — Submit auto-advances. Keeping it visible
      // would just confuse the flow.
      nextBtn.style.display = "none";
      // Relabel Submit so the auto-advance is obvious. On the last
      // question Submit closes out the test via the Finish confirm.
      if (!ans.submitted && submitBtn) {
        submitBtn.textContent = isLast ? "Submit & finish ›" : "Submit & next ›";
      }
      if (submitBtn) submitBtn.style.display = "";
    } else if (state.finished) {
      // Review mode after Finish — no more submitting. Free navigation.
      nextBtn.style.display = "";
      nextBtn.disabled = state.index === total - 1;
      $("#backBtn").disabled = state.index === 0;
      if (submitBtn) submitBtn.style.display = "none";
    } else {
      // Practice mode default.
      nextBtn.style.display = "";
      $("#backBtn").disabled = state.index === 0;
      nextBtn.disabled = false;
      if (submitBtn) {
        submitBtn.style.display = "";
        submitBtn.textContent = "Submit answer";
      }
    }
    // On the last question the Next button is repurposed as "Finish test"
    // so users don't have to scroll up to the small Finish button. The
    // onNextClicked handler routes to onFinish based on data-role.
    nextBtn.dataset.role = isLast ? "finish" : "next";
    nextBtn.textContent = isLast ? "Finish test ›" : "Next question ›";

    // Active sidebar item
    document.querySelectorAll(".qlist li").forEach((el) => {
      el.classList.toggle("active", parseInt(el.dataset.index, 10) === state.index);
    });

    // Refresh vote stats — but only in practice. In exam mode the vote
    // bar is hidden anyway and the network call is wasted.
    if (!isExamMode()) loadVoteStats(q.id);

    // If the user already initiated Finish in exam mode AND hasn't
    // finalized yet, re-assert the finish-only lockdown. After finalize
    // we're in review mode and the lockdown shouldn't fire.
    if (isExamMode() && state.finishInitiated && !state.finished) applyExamFinishOnlyLockdown();
  }

  /* ===========================================================
     Per-question voting (👍 / 👎)
     =========================================================== */
  // Verifier reason lists shown on vote. Per the spec these guide the
  // verifier toward consistent labels; "Other…" lets them type free text.
  const VERIFIER_REASONS_UP = [
    "Matches real-world questions",
    "Close to real exam questions",
    "Well-written question",
  ];
  const VERIFIER_REASONS_DOWN = [
    "Doesn't match real exam questions",
    "Answer is not correct",
    "Question is unclear / ambiguous",
    "Outdated content",
  ];

  function applyVoteUI(stats) {
    const up = document.getElementById("voteUpBtn");
    const dn = document.getElementById("voteDownBtn");
    const uc = document.getElementById("voteUpCount");
    const dc = document.getElementById("voteDownCount");
    if (!up || !dn) return;
    if (uc) uc.textContent = stats.up || 0;
    if (dc) dc.textContent = stats.down || 0;
    up.classList.toggle("is-selected", stats.myVote === 1);
    dn.classList.toggle("is-selected", stats.myVote === -1);
    // Show the reason next to the active button (verifier UX).
    const reasonNote = document.getElementById("voteReasonNote");
    if (reasonNote) {
      if (stats.myReason && stats.myVote !== 0) {
        reasonNote.textContent = "Your reason: " + stats.myReason;
        reasonNote.style.display = "";
      } else {
        reasonNote.style.display = "none";
      }
    }
  }
  async function loadVoteStats(questionId) {
    if (!questionId) return;
    try {
      const res = await fetch("/api/questions/" + questionId + "/vote", { credentials: "same-origin" });
      if (!res.ok) return;
      const data = await res.json();
      // Track which questions the current user has voted on — gates verifier
      // navigation. Server is source of truth so this survives reloads.
      if (data && data.myVote && data.myVote !== 0) {
        state.votedQuestionIds.add(questionId);
      } else {
        state.votedQuestionIds.delete(questionId);
      }
      applyVoteUI(data);
    } catch (e) { /* ignore */ }
  }
  function isVerifier() {
    return state.user && state.user.role === "VERIFIER";
  }
  function promptVerifierReason(direction) {
    // Open the modal dropdown picker. Resolves to the chosen reason string
    // (preset label or "Other" free text), or null if the verifier cancels.
    return new Promise((resolve) => {
      const modal  = document.getElementById("verifierReasonModal");
      const title  = document.getElementById("verifierReasonTitle");
      const select = document.getElementById("verifierReasonSelect");
      const otherWrap  = document.getElementById("verifierReasonOtherLabel");
      const otherInput = document.getElementById("verifierReasonOther");
      const submitBtn  = document.getElementById("verifierReasonSubmit");
      const cancelBtn  = document.getElementById("verifierReasonCancel");
      if (!modal || !select || !submitBtn || !cancelBtn) { resolve(null); return; }

      title.textContent = direction === 1 ? "Reason for your thumbs-up" : "Reason for your thumbs-down";

      const presets = direction === 1 ? VERIFIER_REASONS_UP : VERIFIER_REASONS_DOWN;
      select.innerHTML = '<option value="">— Pick a reason —</option>';
      presets.forEach(function (r) {
        const opt = document.createElement("option");
        opt.value = r;
        opt.textContent = r;
        select.appendChild(opt);
      });
      const otherOpt = document.createElement("option");
      otherOpt.value = "__OTHER__";
      otherOpt.textContent = "Other (specify)";
      select.appendChild(otherOpt);

      select.value = "";
      otherInput.value = "";
      otherWrap.style.display = "none";
      modal.classList.remove("hidden");

      function onSelectChange() {
        const showOther = select.value === "__OTHER__";
        otherWrap.style.display = showOther ? "" : "none";
        if (showOther) setTimeout(function () { otherInput.focus(); }, 30);
      }
      function done(value) {
        modal.classList.add("hidden");
        select.removeEventListener("change", onSelectChange);
        submitBtn.removeEventListener("click", onSubmit);
        cancelBtn.removeEventListener("click", onCancel);
        document.removeEventListener("keydown", onKey);
        resolve(value);
      }
      function onSubmit() {
        let reason = select.value;
        if (!reason) { select.focus(); return; }
        if (reason === "__OTHER__") {
          reason = otherInput.value.trim();
          if (!reason) { otherInput.focus(); return; }
        }
        done(reason);
      }
      function onCancel() { done(null); }
      function onKey(e) {
        if (e.key === "Escape") onCancel();
        else if (e.key === "Enter" && (e.target === select || e.target === otherInput)) {
          e.preventDefault();
          onSubmit();
        }
      }

      select.addEventListener("change", onSelectChange);
      submitBtn.addEventListener("click", onSubmit);
      cancelBtn.addEventListener("click", onCancel);
      document.addEventListener("keydown", onKey);
      setTimeout(function () { select.focus(); }, 30);
    });
  }
  async function castVote(value) {
    const q = state.questions[state.index];
    if (!q) return;
    let reason = null;
    if (isVerifier()) {
      reason = await promptVerifierReason(value);
      if (!reason) return; // verifier cancelled — abort vote
    }
    try {
      const res = await fetch("/api/questions/" + q.id + "/vote", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "same-origin",
        body: JSON.stringify({ value: value, reason: reason }),
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        if (err && err.error) showTransientWarning(err.error);
        return;
      }
      const data = await res.json();
      // Successful verifier vote unlocks navigation away from this question.
      if (data && data.myVote && data.myVote !== 0) {
        state.votedQuestionIds.add(q.id);
      } else {
        state.votedQuestionIds.delete(q.id);
      }
      applyVoteUI(data);
    } catch (e) { /* ignore */ }
  }

  // Verifiers must vote on every question they visit before leaving it. Returns
  // true if the current verifier is missing a vote on the active question.
  function verifierNeedsVote() {
    if (!isVerifier()) return false;
    const q = state.questions[state.index];
    if (!q) return false;
    return !state.votedQuestionIds.has(q.id);
  }

  function showVerifierVoteBlock() {
    const b = $("#resultBanner");
    if (!b) return;
    b.classList.remove("hidden", "success");
    b.classList.add("error");
    b.textContent = "Verifier action required: please submit 👍 or 👎 (with a reason) for this question before moving on.";
    // Pulse the vote bar so it's obvious where to act.
    const bar = document.getElementById("voteBar");
    if (bar) {
      bar.classList.add("vote-bar-attention");
      setTimeout(() => bar.classList.remove("vote-bar-attention"), 1600);
    }
  }

  function applyResultStyling(q, ans) {
    const correctIds = new Set(ans.correctChoiceIds || []);
    const selectedIds = ans.selected;
    document.querySelectorAll(".choices .choice").forEach((el) => {
      const input = el.querySelector("input");
      const cid = parseInt(input.value, 10);
      el.classList.add("locked");
      input.disabled = true;
      el.classList.remove("correct", "incorrect");
      const old = el.querySelector(".pill"); if (old) old.remove();

      if (correctIds.has(cid)) {
        el.classList.add("correct");
      } else if (selectedIds.has(cid)) {
        el.classList.add("incorrect");
      }
    });
  }

  function showBanner(correct) {
    const b = $("#resultBanner");
    b.classList.remove("hidden", "success", "error");
    b.classList.add(correct ? "success" : "error");
    b.textContent = correct
      ? "Correct! Review the explanation and resources to learn more."
      : "Incorrect answer. Review the explanation and resources to learn more.";
  }

  function showExplanation(q, ans) {
    const box = $("#explanationBox");
    box.classList.remove("hidden");

    // Render explanation as paragraphs, splitting on blank lines.
    const text = (ans.explanation || q.explanation || "").trim();
    const target = $("#explanationText");
    target.innerHTML = "";
    if (text) {
      text.split(/\n\s*\n/).forEach((para) => {
        const p = document.createElement("p");
        p.textContent = para.trim();
        target.appendChild(p);
      });
    }

    // Help URL goes after the explanation.
    const url = ans.helpUrl || q.helpUrl;
    const linkWrap = document.querySelector(".explanation-link");
    const link = $("#helpLink");
    if (url) {
      link.href = url;
      link.textContent = url;
      if (linkWrap) linkWrap.style.display = "";
    } else if (linkWrap) {
      linkWrap.style.display = "none";
    }
  }

  async function onSubmit() {
    if (timer.state === "expired") {
      showTransientWarning("Time's up — the test is locked.");
      return;
    }
    const q = state.questions[state.index];
    const ans = state.answers.get(q.id);
    if (ans.selected.size === 0) {
      showTransientWarning("Please select an answer first.");
      return;
    }
    try {
      const res = await fetch("/api/questions/" + q.id + "/submit", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ selectedChoiceIds: Array.from(ans.selected) }),
      });
      if (!res.ok) throw new Error("Submit failed");
      const data = await res.json();
      ans.submitted = true;
      ans.correct = data.correct;
      ans.correctChoiceIds = data.correctChoiceIds;
      ans.explanation = data.explanation;
      ans.helpUrl = data.helpUrl;
      renderSidebar();
      renderQuestion();
      // EXAM mode: auto-advance after a successful submit. On the last
      // question this opens the Finish confirm (data-role drives it).
      // Small delay lets the submission visually register before the
      // next question swaps in.
      if (isExamMode()) {
        setTimeout(function () { onNextClicked(); }, 200);
      }
    } catch (e) {
      showTransientWarning("Could not submit: " + e.message);
    }
  }

  function showTransientWarning(msg) {
    const b = $("#resultBanner");
    b.classList.remove("hidden", "success");
    b.classList.add("error");
    b.textContent = msg;
    setTimeout(() => {
      const cur = state.questions[state.index];
      const a = state.answers.get(cur.id);
      if (!a.submitted) b.classList.add("hidden");
    }, 2200);
  }

  function goTo(i) {
    if (i < 0 || i >= state.questions.length) return;
    if (i === state.index) return;
    // LIVE EXAM mode locks navigation to "forward by one after
    // submitting the current answer". Sidebar jumps and Back are
    // both no-ops here so a user can't cheat by skipping back to
    // compare answers. Post-finish (state.finished == true) the test
    // is over and review-mode navigation is free.
    if (isExamMode() && !state.finished) {
      if (i !== state.index + 1) return;
      const curQ = state.questions[state.index];
      const curAns = state.answers.get(curQ.id);
      if (!curAns || !curAns.submitted) return;
    }
    // Verifier role: block any navigation (Next button, Back, or sidebar click)
    // until the current question has a vote with reason. Show the on-page error.
    if (verifierNeedsVote()) {
      showVerifierVoteBlock();
      return;
    }
    // Jumping forward in the sidebar is an implicit skip of every
    // unanswered question between here and the target — mark them
    // visited so they show as "Skipped" in the sidebar. Jumping
    // backward is plain navigation and never auto-skips anything.
    // Submitted answers are left alone (visited is already true).
    if (i > state.index) {
      for (let j = state.index + 1; j < i; j++) {
        const interQ = state.questions[j];
        const interAns = state.answers.get(interQ.id);
        if (interAns && !interAns.submitted) {
          interAns.visited = true;
        }
      }
    }
    state.index = i;
    renderQuestion();
    renderSidebar();
    document.querySelector(".main-body").scrollTo({ top: 0, behavior: "smooth" });
  }

  function countCompleted() {
    let n = 0;
    state.answers.forEach((a) => { if (a.submitted) n++; });
    return n;
  }

  /** Passing-score threshold — set from exam metadata at init time. */
  let PASSING_PCT = 65;

  function onFinish() {
    // If results are already showing (test is finished), the button is greyed
    // out — but guard anyway so a keyboard / programmatic click can't fire it.
    if (state.finished) return;
    // Verifiers must vote on the current question first.
    if (verifierNeedsVote()) {
      showVerifierVoteBlock();
      return;
    }

    const completed = countCompleted();
    const total = state.questions.length;
    // EXAM mode: lock the action bar to "only Finish" while the confirm
    // is open, so the user can't go back to answering while they're
    // deciding. If they cancel, we release the lockdown — clicking
    // Finish was an exploratory action, not a commitment.
    if (isExamMode()) {
      state.finishInitiated = true;
      applyExamFinishOnlyLockdown();
    }
    showConfirmPanel({
      title: "Finish the test now?",
      body: completed < total
        ? "You've answered " + completed + " of " + total + ". Unanswered questions count as incorrect. " +
          "Once you finish, you can't return to the questions — only Reset will let you retake the test."
        : "All " + total + " questions answered. Once you finish, you can't return to the questions — " +
          "only Reset will let you retake the test.",
      confirmLabel: "Finish test",
      onConfirm: () => { hideConfirmPanel(); finalizeTest(); },
      onCancel: () => {
        // Restore the in-question controls so the user can keep
        // answering. Only fires in exam mode — practice mode never
        // applies the lockdown in the first place.
        if (isExamMode()) {
          state.finishInitiated = false;
          releaseExamFinishOnlyLockdown();
        }
      },
    });
  }

  /** Reverse applyExamFinishOnlyLockdown — bring back the in-question
   *  controls so the user can continue answering. Called from the
   *  Finish confirm's onCancel; renderQuestion paints the right
   *  enabled / disabled / hidden state from scratch so we don't need
   *  to undo each style mutation manually. */
  function releaseExamFinishOnlyLockdown() {
    const notice = document.getElementById("examFinishNotice");
    if (notice && notice.parentNode) notice.parentNode.removeChild(notice);
    // Show the action bar back. renderQuestion will set the right
    // disabled / hidden state per mode for Submit / Back / Next /
    // choices, including the exam-mode rules that hide Next and lock
    // Back. We just need the elements to be visible again.
    ["#submitBtn", "#backBtn", "#voteBar"].forEach(function (sel) {
      const el = $(sel);
      if (el) el.style.display = "";
    });
    document.querySelectorAll("#choices input").forEach(function (inp) {
      inp.disabled = false;
    });
    renderQuestion();
  }

  /** Hide every in-question control (Submit, Back, Next, choice inputs,
   *  voting) so only the top-of-page Finish button remains. Called once
   *  in exam mode the moment Finish is first clicked — even if the
   *  user cancels the confirm, this state persists so they can't go
   *  back to answering. */
  function applyExamFinishOnlyLockdown() {
    const hideIds = ["#submitBtn", "#nextBtn", "#backBtn", "#voteBar"];
    hideIds.forEach(function (sel) {
      const el = $(sel);
      if (el) el.style.display = "none";
    });
    // Lock the choice inputs so the user can't change their answer
    // (or pick one if they hadn't yet).
    document.querySelectorAll("#choices input").forEach(function (inp) {
      inp.disabled = true;
    });
    // Replace the action bar with a clear message — gives the user
    // a single, unmistakable next step.
    const notice = document.getElementById("examFinishNotice");
    if (!notice) {
      const bar = document.querySelector(".actions-bar");
      if (bar) {
        const p = document.createElement("p");
        p.id = "examFinishNotice";
        p.style.cssText = "margin: 12px 0; padding: 10px 14px; background: #fef3c7; border-left: 3px solid #f59e0b; color: #92400e; font-size: 14px; border-radius: 6px;";
        p.textContent = "You've initiated Finish. Click \"Finish test\" in the header to confirm — you can't return to answering.";
        bar.parentNode.insertBefore(p, bar);
      }
    }
  }

  function finalizeTest() {
    const total = state.questions.length;
    let correctCount = 0;
    state.answers.forEach((a) => { if (a.submitted && a.correct) correctCount++; });
    const completed = countCompleted();
    const incorrect = completed - correctCount;
    const unanswered = total - completed;
    // Score is correct / total (not / completed) — unanswered counts against you,
    // matching the real-exam grading.
    const scorePct = total === 0 ? 0 : Math.round((correctCount / total) * 100);

    $("#resScore").textContent = scorePct + "%";
    $("#resScoreLabel").textContent = "Score (" + correctCount + " of " + total + " correct)";
    $("#resCompleted").textContent = completed + " / " + total;
    $("#resCorrect").textContent = String(correctCount);
    $("#resIncorrect").textContent = String(incorrect);
    $("#resUnanswered").textContent = String(unanswered);

    const passed = scorePct >= PASSING_PCT;
    const banner = $("#passFailBanner");
    const pill = $("#passFailPill");
    const detail = $("#passFailDetail");
    banner.classList.remove("hidden", "pass", "fail");
    banner.classList.add(passed ? "pass" : "fail");
    pill.textContent = passed ? "PASS" : "FAIL";
    detail.textContent = passed
      ? "Your " + scorePct + "% beats the " + PASSING_PCT + "% passing score."
      : "You scored " + scorePct + "% — the passing score is " + PASSING_PCT + "%. Reset to try again.";

    state.finished = true;
    setFinishLocked(true);
    // EXAM mode: keep the topbar locked through the results screen so
    // the proctored-exam feel doesn't break the moment Finish lands.
    // The user exits through the dedicated "Exit exam" button on the
    // results panel (revealed below), which releases the lock and routes
    // them to their My reports detail page. PRACTICE mode never had
    // the lock so this is a no-op for it.
    if (!isExamMode()) releaseExamNavigationLock();
    if (isExamMode()) {
      // The finish is no longer "in flight" — clear the finish-only
      // lockdown artifacts (yellow notice, disabled choice inputs)
      // so 'Back to test' presents a clean review screen.
      state.finishInitiated = false;
      const notice = document.getElementById("examFinishNotice");
      if (notice && notice.parentNode) notice.parentNode.removeChild(notice);
      document.querySelectorAll("#choices input").forEach(function (inp) {
        inp.disabled = false;
      });
      // Reveal the question-list sidebar again — review mode lets the
      // user click through every question to see what they picked.
      const layout = document.getElementById("layout");
      if (layout) layout.classList.remove("exam-mode");
      // Show the explicit exit button; hide Retest (you can't reset
      // an exam, you have to exit and start a new one). Back to test
      // now shows feedback (renderQuestion treats state.finished as
      // "open the answers for review").
      const exit = document.getElementById("examExitBtn");
      const retest = document.getElementById("retestBtn");
      if (exit) exit.style.display = "";
      if (retest) retest.style.display = "none";
    }

    $("#quizArea").classList.add("hidden");
    $("#resultsPanel").classList.remove("hidden");
    document.querySelector(".main-body").scrollTo({ top: 0, behavior: "smooth" });

    recordAttempt(total, correctCount, incorrect, unanswered);
  }

  function recordAttempt(total, correctCount, incorrect, unanswered) {
    if (!examSlug) return;
    const finishedAt = new Date();
    // De-dupe: only POST once per finalize. Back-to-test → re-finalize records again.
    if (state.lastRecordedFinishAt && (finishedAt - state.lastRecordedFinishAt) < 1000) return;
    state.lastRecordedFinishAt = finishedAt;
    const started = state.startedAt || finishedAt;

    // Snapshot per-question detail so the user's My Reports drill-down can
    // show them which questions they got right/wrong + the explanations.
    // Only submitted questions are included; unanswered ones get no row.
    const answers = [];
    state.questions.forEach((q) => {
      const a = state.answers.get(q.id);
      if (!a || !a.submitted) return;
      answers.push({
        questionId: q.id,
        selectedChoiceIds: Array.from(a.selected),
        correct: !!a.correct,
      });
    });

    fetch("/api/test-attempts", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "same-origin",
      body: JSON.stringify({
        examSlug: examSlug,
        startedAt: started.toISOString(),
        finishedAt: finishedAt.toISOString(),
        totalQuestions: total,
        correctCount: correctCount,
        incorrectCount: incorrect,
        unansweredCount: unanswered,
        // Snapshot of the exact set + order served this session, so the
        // user can retake this same test later via /index.html?retake=<id>.
        questionIds: state.questions.map(function (q) { return q.id; }),
        mode: testMode.toUpperCase(),
        answers: answers,
      }),
    }).catch(function () { /* don't block UX on a recording failure */ });
  }

  /** Grey out the Finish button when the test has been finished. Submit-button
   *  state is intentionally NOT touched here — renderQuestion() owns that and
   *  re-evaluates it from (ans.submitted || timer.state === "expired"). */
  function setFinishLocked(locked) {
    const fb = $("#finishBtn");
    if (fb) {
      fb.disabled = locked;
      fb.classList.toggle("is-locked", locked);
      fb.setAttribute("title", locked ? "Test already finished — reset to retake." : "Finish the test and see your score.");
    }
  }

  function hideResultsPanel() {
    $("#resultsPanel").classList.add("hidden");
    $("#resultsPanel").classList.remove("detail-mode");
    $("#resultsDetail").classList.add("hidden");
    $("#resultsSummary").classList.remove("hidden");
    $("#quizArea").classList.remove("hidden");
    // Going back to the test treats the test as "in progress again" — the user
    // can keep answering and re-finish later. Re-enable Finish; keep answers
    // and the timer as-is (they're still good).
    state.finished = false;
    setFinishLocked(false);
    document.querySelector(".main-body").scrollTo({ top: 0, behavior: "smooth" });
  }

  function showResultsDetail(filter) {
    detail.filter = filter;
    detail.page = 0;
    detail.items = state.questions
      .map((q) => ({ q, ans: state.answers.get(q.id) }))
      .filter(({ ans }) => ans && ans.submitted && (filter === "correct" ? ans.correct : !ans.correct));
    $("#resultsDetailTitle").textContent = filter === "correct" ? "Correct answers" : "Incorrect answers";
    $("#resultsPanel").classList.add("detail-mode");
    $("#resultsSummary").classList.add("hidden");
    $("#resultsDetail").classList.remove("hidden");
    renderDetailPage();
    document.querySelector(".main-body").scrollTo({ top: 0, behavior: "smooth" });
  }

  function hideResultsDetail() {
    $("#resultsPanel").classList.remove("detail-mode");
    $("#resultsDetail").classList.add("hidden");
    $("#resultsSummary").classList.remove("hidden");
    document.querySelector(".main-body").scrollTo({ top: 0, behavior: "smooth" });
  }

  function changeDetailPage(delta) {
    const totalPages = Math.max(1, Math.ceil(detail.items.length / detail.pageSize));
    const next = detail.page + delta;
    if (next < 0 || next >= totalPages) return;
    detail.page = next;
    renderDetailPage();
    document.querySelector(".main-body").scrollTo({ top: 0, behavior: "smooth" });
  }

  function renderDetailPage() {
    const { items, page, pageSize } = detail;
    const wrap = $("#resultsDetailItems");
    wrap.innerHTML = "";
    const start = page * pageSize;
    const slice = items.slice(start, start + pageSize);
    if (slice.length === 0) {
      const empty = document.createElement("div");
      empty.className = "results-empty";
      empty.textContent = detail.filter === "correct"
        ? "You haven't answered any questions correctly yet."
        : "No incorrect answers — nice work.";
      wrap.appendChild(empty);
    } else {
      slice.forEach(({ q, ans }) => wrap.appendChild(buildDetailItem(q, ans)));
    }
    const totalPages = Math.max(1, Math.ceil(items.length / pageSize));
    const countLabel = items.length + " question" + (items.length === 1 ? "" : "s");
    $("#resultsPageInfo").textContent = "Page " + (page + 1) + " of " + totalPages + " · " + countLabel;
    $("#resultsPrevBtn").disabled = page === 0;
    $("#resultsNextBtn").disabled = page >= totalPages - 1;
  }

  function buildDetailItem(q, ans) {
    const article = document.createElement("article");
    article.className = "results-item " + (ans.correct ? "correct" : "incorrect");

    const correctIds = new Set(ans.correctChoiceIds || []);
    const correctChoices = q.choices.filter((c) => correctIds.has(c.id));
    const userChoices = q.choices.filter((c) => ans.selected.has(c.id));

    const fmt = (arr) => arr.length === 0 ? "—" : arr.map((c) => c.label + ". " + c.text).join("; ");

    const fullExp = (ans.explanation || q.explanation || "").trim();
    const firstPara = fullExp ? fullExp.split(/\n\s*\n/)[0].trim() : "";

    const badge = ans.correct ? "Correct" : "Incorrect";
    const badgeCls = ans.correct ? "correct" : "incorrect";

    const header = document.createElement("header");
    header.className = "results-item-header";
    header.innerHTML =
      '<span class="results-item-num">Question ' + q.number + '</span>' +
      '<span class="results-item-badge ' + badgeCls + '">' + badge + '</span>';
    article.appendChild(header);

    const qtext = document.createElement("div");
    qtext.className = "results-item-text";
    qtext.textContent = q.text;
    article.appendChild(qtext);

    const yourRow = document.createElement("div");
    yourRow.className = "results-item-row";
    yourRow.innerHTML = '<span class="results-item-label">Your answer:</span> ';
    yourRow.appendChild(document.createTextNode(fmt(userChoices)));
    article.appendChild(yourRow);

    if (!ans.correct) {
      const correctRow = document.createElement("div");
      correctRow.className = "results-item-row";
      correctRow.innerHTML = '<span class="results-item-label">Correct answer:</span> ';
      correctRow.appendChild(document.createTextNode(fmt(correctChoices)));
      article.appendChild(correctRow);
    }

    if (firstPara) {
      const p = document.createElement("p");
      p.className = "results-item-explanation";
      p.textContent = firstPara;
      article.appendChild(p);
    }

    return article;
  }

  function showConfirmPanel(opts) {
    $("#confirmTitle").textContent = opts.title;
    $("#confirmBody").textContent = opts.body;
    $("#confirmOkBtn").textContent = opts.confirmLabel || "Confirm";
    pendingConfirm = opts.onConfirm;
    // Optional callback fired when the user clicks Cancel — lets the
    // caller undo any pre-confirm side effects (e.g. exam-mode Finish
    // sets a finish-only lockdown that has to be reversed on cancel).
    pendingCancel = typeof opts.onCancel === "function" ? opts.onCancel : null;
    const altBtn = $("#confirmAltBtn");
    if (opts.altLabel && typeof opts.onAlt === "function") {
      altBtn.textContent = opts.altLabel;
      altBtn.classList.remove("hidden");
      pendingAlt = opts.onAlt;
    } else {
      altBtn.classList.add("hidden");
      pendingAlt = null;
    }
    confirmReturnTo = !$("#resultsPanel").classList.contains("hidden") ? "results" : "quiz";
    $("#quizArea").classList.add("hidden");
    $("#resultsPanel").classList.add("hidden");
    $("#confirmPanel").classList.remove("hidden");
    document.querySelector(".main-body").scrollTo({ top: 0, behavior: "smooth" });
  }

  function restoreFromConfirm() {
    if (confirmReturnTo === "results") {
      $("#resultsPanel").classList.remove("hidden");
    } else {
      $("#quizArea").classList.remove("hidden");
    }
  }

  function hideConfirmPanel() {
    $("#confirmPanel").classList.add("hidden");
    pendingConfirm = null;
    pendingAlt = null;
    pendingCancel = null;
    restoreFromConfirm();
  }

  function onConfirmCancel() {
    // Fire the per-confirm cancel callback BEFORE hideConfirmPanel
    // clears it. Used by exam-mode Finish to undo its finish-only
    // lockdown when the user changes their mind.
    const cb = pendingCancel;
    pendingCancel = null;
    if (typeof cb === "function") {
      try { cb(); } catch (e) { /* never block the cancel itself */ }
    }
    hideConfirmPanel();
  }

  /** Next button — if the current question hasn't been submitted, warn the
   *  user that they're about to skip it (it'll show as Skipped in the sidebar
   *  and count against them on the results screen). Confirmation proceeds;
   *  cancel keeps them on the same question to answer first. */
  function onNextClicked() {
    // The Next button is repurposed as "Finish test" on the last question
    // (set in renderQuestion via data-role). Route accordingly so users
    // don't have to scroll up to the smaller Finish button.
    if ($("#nextBtn").dataset.role === "finish") {
      onFinish();
      return;
    }
    const total = state.questions.length;
    if (state.index >= total - 1) return;
    const q = state.questions[state.index];
    const ans = state.answers.get(q.id);
    const submitted = ans && ans.submitted;
    if (submitted) {
      goTo(state.index + 1);
      return;
    }
    // EXAM mode: no skip warning, no advance. The button is disabled in
    // renderQuestion when there's no submitted answer, so we should
    // never hit this path — but guard anyway in case a stray keystroke
    // triggers it.
    if (isExamMode()) return;
    showConfirmPanel({
      title: "Skip this question?",
      body: "You haven't submitted an answer yet. If you skip, this question will be marked as Skipped — you can come back to it from the sidebar before you finish the test.",
      confirmLabel: "Skip and continue",
      onConfirm: () => goTo(state.index + 1)
    });
  }

  function onConfirmOk() {
    const cb = pendingConfirm;
    pendingConfirm = null;
    pendingAlt = null;
    pendingCancel = null;
    $("#confirmPanel").classList.add("hidden");
    // Restore the panel that was visible before we opened the confirm
    // (quiz or results). The callback can still transition to a
    // different view if it wants — but without this, the quiz stays
    // hidden after "Skip and continue" and the sidebar appears dead.
    restoreFromConfirm();
    if (typeof cb === "function") cb();
  }

  function onConfirmAlt() {
    const cb = pendingAlt;
    pendingAlt = null;
    pendingConfirm = null;
    pendingCancel = null;
    $("#confirmPanel").classList.add("hidden");
    restoreFromConfirm();
    if (typeof cb === "function") cb();
  }

  /* ===========================================================
     Timer — 90 min countdown with pause/resume,
     persisted in localStorage keyed by user email.
     =========================================================== */

  function initTimer() {
    const email = (state.user && state.user.email) ? state.user.email : "anon";
    timer.storageKey = "sfquiz:timer:" + (examSlug || "default") + ":" + email;
    const saved = loadTimerState();
    if (saved) {
      Object.assign(timer, saved);
      if (timer.state === "running" && Date.now() >= timer.endTime) {
        // The grace period elapsed while the page was closed.
        expireTimer();
      } else if (timer.state === "running") {
        startTicking();
      } else if (timer.state === "paused") {
        // Stay paused — the user can resume via the timer-toggle button.
      } else {
        // "expired" or "not_started" or anything weird: auto-start a fresh
        // timer so the test page is never frozen with no way forward.
        timer.remainingMs = TOTAL_MS;
        timer.state = "not_started";
        timer.endTime = 0;
        startTimer();
      }
    } else {
      // First-time: auto-start the test timer.
      timer.remainingMs = TOTAL_MS;
      timer.state = "not_started";
      startTimer();
    }
    renderTimer();
  }

  function startTimer() {
    if (timer.state === "expired") return;
    timer.endTime = Date.now() + timer.remainingMs;
    timer.state = "running";
    saveTimerState();
    startTicking();
    renderTimer();
  }

  function pauseTimer() {
    if (timer.state !== "running") return;
    timer.remainingMs = Math.max(0, timer.endTime - Date.now());
    timer.state = "paused";
    saveTimerState();
    stopTicking();
    renderTimer();
  }

  function expireTimer() {
    timer.state = "expired";
    timer.remainingMs = 0;
    timer.endTime = 0;
    saveTimerState();
    stopTicking();
    renderTimer();
    lockOnExpire();
  }

  function onTimerToggle() {
    if (timer.state === "running") pauseTimer();
    else if (timer.state === "paused" || timer.state === "not_started") startTimer();
  }

  function startTicking() {
    if (timer.intervalId) return;
    timer.intervalId = setInterval(() => {
      if (timer.state !== "running") return;
      const remaining = timer.endTime - Date.now();
      if (remaining <= 0) {
        expireTimer();
      } else {
        renderTimer();
      }
    }, 1000);
  }

  function stopTicking() {
    if (timer.intervalId) {
      clearInterval(timer.intervalId);
      timer.intervalId = null;
    }
  }

  function renderTimer() {
    const el = $("#timer");
    const text = $("#timerText");
    const btn = $("#timerToggle");
    if (!el || !text || !btn) return;

    let ms;
    if (timer.state === "running")      ms = Math.max(0, timer.endTime - Date.now());
    else if (timer.state === "expired") ms = 0;
    else                                 ms = timer.remainingMs;

    text.textContent = formatMs(ms);
    el.classList.remove("warning", "expired", "paused");
    if (timer.state === "expired") {
      el.classList.add("expired");
      btn.textContent = "Time's up";
      btn.disabled = true;
    } else if (timer.state === "paused") {
      el.classList.add("paused");
      btn.textContent = "Resume";
      btn.disabled = false;
    } else {
      if (ms <= WARN_MS) el.classList.add("warning");
      btn.textContent = "Pause";
      btn.disabled = false;
    }
  }

  function formatMs(ms) {
    const total = Math.max(0, Math.floor(ms / 1000));
    const m = Math.floor(total / 60);
    const s = total % 60;
    return String(m).padStart(2, "0") + ":" + String(s).padStart(2, "0");
  }

  function saveTimerState() {
    if (!timer.storageKey) return;
    try {
      localStorage.setItem(timer.storageKey, JSON.stringify({
        state: timer.state,
        endTime: timer.endTime,
        remainingMs: timer.remainingMs,
      }));
    } catch (e) { /* ignore quota errors */ }
  }

  function loadTimerState() {
    if (!timer.storageKey) return null;
    try {
      const raw = localStorage.getItem(timer.storageKey);
      if (!raw) return null;
      const obj = JSON.parse(raw);
      if (typeof obj.remainingMs !== "number") return null;
      return obj;
    } catch (e) { return null; }
  }

  function lockOnExpire() {
    $("#submitBtn").disabled = true;
    const b = $("#resultBanner");
    b.classList.remove("hidden", "success");
    b.classList.add("error");
    b.textContent = "Time's up. The test is locked. Click Finish test to see your score.";
  }

  /* ===========================================================
     Reset Test (shuffle + clear answers + reset timer)
     and Reset Timer (timer only)
     =========================================================== */

  function onResetTest() {
    showConfirmPanel({
      title: "Reset and reshuffle the test?",
      body: "This will reshuffle all questions, clear your answers, and reset the timer to "
            + formatMs(TOTAL_MS) + ".",
      confirmLabel: "Reset test",
      onConfirm: doResetTest,
    });
  }

  function doResetTest() {
    shuffleInPlace(state.questions);
    state.questions.forEach((q, i) => {
      q.number = i + 1;
      state.answers.set(q.id, { selected: new Set(), submitted: false, correct: false, visited: false });
    });
    state.index = 0;
    state.finished = false;
    state.startedAt = new Date();
    state.lastRecordedFinishAt = null;
    // Past-vote cache is stale once we reshuffle — let loadVoteStats refresh it.
    state.votedQuestionIds = new Set();
    setFinishLocked(false);
    resetTimerInternal();
    const banner = $("#passFailBanner");
    if (banner) banner.classList.add("hidden");
    $("#resultsPanel").classList.add("hidden");
    $("#resultsPanel").classList.remove("detail-mode");
    $("#resultsDetail").classList.add("hidden");
    $("#resultsSummary").classList.remove("hidden");
    $("#quizArea").classList.remove("hidden");
    renderSidebar();
    renderQuestion();
    document.querySelector(".main-body").scrollTo({ top: 0 });
  }

  function onResetTimer() {
    const dur = formatMs(TOTAL_MS);
    showConfirmPanel({
      title: "Reset the timer to " + dur + "?",
      body: "Choose 'Reset timer' to keep your progress and restart the clock, or 'Restart entire test' to reshuffle "
            + practiceCount + " fresh questions and start over.",
      confirmLabel: "Reset timer",
      onConfirm: () => {
        resetTimerInternal();
        restoreFromConfirm();
      },
      altLabel: "Restart entire test",
      onAlt: doResetTest,
    });
  }

  function resetTimerInternal() {
    stopTicking();
    timer.state = "not_started";
    timer.endTime = 0;
    timer.remainingMs = TOTAL_MS;
    saveTimerState();
    // Force-clear any "Time's up" banner left over from a previous expiry —
    // renderQuestion's else-branch only hides it when ans.submitted is false,
    // and we want a clean slate regardless.
    const b = $("#resultBanner");
    if (b) {
      b.classList.add("hidden");
      b.classList.remove("error", "success");
      b.textContent = "";
    }
    const sb = $("#submitBtn");
    if (sb) sb.disabled = false;
    startTimer(); // immediately starts running with fresh duration
    if (state.questions.length) renderQuestion();
  }

  function shuffleInPlace(arr) {
    for (let i = arr.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      const tmp = arr[i];
      arr[i] = arr[j];
      arr[j] = tmp;
    }
  }

  /* ===========================================================
     Sidebar resizer — drag the divider to adjust sidebar width.
     Width persists in localStorage.
     =========================================================== */

  const SIDEBAR_KEY = "sfquiz:sidebar-width";
  const SIDEBAR_MIN = 220;
  const SIDEBAR_MAX = 640;

  function initSidebarWidth() {
    const saved = parseInt(localStorage.getItem(SIDEBAR_KEY) || "", 10);
    if (Number.isFinite(saved) && saved >= SIDEBAR_MIN && saved <= SIDEBAR_MAX) {
      setSidebarWidth(saved);
    }
  }

  function setSidebarWidth(px) {
    document.documentElement.style.setProperty("--sidebar-w", px + "px");
  }

  function initResizer() {
    const handle = $("#resizer");
    const layout = $("#layout");
    if (!handle || !layout) return;

    let dragging = false;
    let startX = 0;
    let startWidth = 0;

    const beginDrag = (clientX) => {
      const sidebar = $("#sidebar");
      startWidth = sidebar.getBoundingClientRect().width;
      startX = clientX;
      dragging = true;
      handle.classList.add("dragging");
      document.body.classList.add("resizing");
    };

    const moveDrag = (clientX) => {
      if (!dragging) return;
      let newW = startWidth + (clientX - startX);
      newW = Math.max(SIDEBAR_MIN, Math.min(SIDEBAR_MAX, newW));
      setSidebarWidth(newW);
    };

    const endDrag = () => {
      if (!dragging) return;
      dragging = false;
      handle.classList.remove("dragging");
      document.body.classList.remove("resizing");
      // Persist final width.
      const sidebar = $("#sidebar");
      const w = Math.round(sidebar.getBoundingClientRect().width);
      try { localStorage.setItem(SIDEBAR_KEY, String(w)); } catch (e) { /* ignore */ }
    };

    handle.addEventListener("mousedown", (e) => { e.preventDefault(); beginDrag(e.clientX); });
    document.addEventListener("mousemove", (e) => moveDrag(e.clientX));
    document.addEventListener("mouseup", endDrag);

    // Touch support.
    handle.addEventListener("touchstart", (e) => {
      if (!e.touches[0]) return;
      beginDrag(e.touches[0].clientX);
    }, { passive: true });
    document.addEventListener("touchmove", (e) => {
      if (!e.touches[0]) return;
      moveDrag(e.touches[0].clientX);
    }, { passive: true });
    document.addEventListener("touchend", endDrag);

    // Keyboard nudge for accessibility.
    handle.addEventListener("keydown", (e) => {
      const sidebar = $("#sidebar");
      const cur = Math.round(sidebar.getBoundingClientRect().width);
      const step = e.shiftKey ? 32 : 8;
      if (e.key === "ArrowLeft")  { e.preventDefault(); setSidebarWidth(Math.max(SIDEBAR_MIN, cur - step)); persistCurrent(); }
      if (e.key === "ArrowRight") { e.preventDefault(); setSidebarWidth(Math.min(SIDEBAR_MAX, cur + step)); persistCurrent(); }
    });

    function persistCurrent() {
      const w = Math.round($("#sidebar").getBoundingClientRect().width);
      try { localStorage.setItem(SIDEBAR_KEY, String(w)); } catch (e) { /* ignore */ }
    }
  }

  document.addEventListener("DOMContentLoaded", init);
})();
