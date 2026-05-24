/* Shared in-page confirmation modal.
 *
 * Replaces browser-native confirm() popups across the admin UI so the
 * page can stay focused and styled consistently. Drop a `data-confirm`
 * attribute (and optionally `data-confirm-title`, `data-confirm-ok`,
 * `data-confirm-cancel`) on any <button> or <a>, include this script,
 * and the action is gated by an in-page modal instead of a popup.
 *
 *   <button type="submit" class="btn btn-danger"
 *           data-confirm="Permanently delete jane@x.com? Cannot be undone."
 *           data-confirm-ok="Delete"
 *           data-confirm-title="Delete user">
 *     Delete
 *   </button>
 *
 * Reuses the .modal-backdrop / .modal-card / .modal-actions classes from
 * style.css so we don't ship duplicate styling.
 */
(function () {
  const MODAL_ID = "__page_confirm_modal";

  function buildModal() {
    let modal = document.getElementById(MODAL_ID);
    if (modal) return modal;
    modal = document.createElement("div");
    modal.id = MODAL_ID;
    modal.className = "modal-backdrop hidden";
    modal.setAttribute("role", "dialog");
    modal.setAttribute("aria-modal", "true");
    modal.setAttribute("aria-labelledby", "__page_confirm_title");
    modal.innerHTML =
      '<div class="modal-card">' +
        '<h3 id="__page_confirm_title">Are you sure?</h3>' +
        '<p class="modal-hint" id="__page_confirm_body" style="white-space: pre-line;"></p>' +
        '<div class="modal-actions">' +
          '<button type="button" class="btn" id="__page_confirm_cancel">Cancel</button>' +
          '<button type="button" class="btn btn-primary" id="__page_confirm_ok">Confirm</button>' +
        '</div>' +
      '</div>';
    document.body.appendChild(modal);
    return modal;
  }

  function showConfirm(opts) {
    return new Promise(function (resolve) {
      const modal = buildModal();
      const title  = document.getElementById("__page_confirm_title");
      const body   = document.getElementById("__page_confirm_body");
      const okBtn  = document.getElementById("__page_confirm_ok");
      const noBtn  = document.getElementById("__page_confirm_cancel");

      title.textContent = opts.title || "Are you sure?";
      body.textContent  = opts.message || "";
      okBtn.textContent = opts.okLabel || "Confirm";
      noBtn.textContent = opts.cancelLabel || "Cancel";

      // Style the OK button to match the originating action — destructive
      // (Delete, Retire, Reject) → red; everything else → blue primary.
      okBtn.classList.remove("btn-primary", "btn-danger", "btn-warning");
      okBtn.classList.add(opts.okStyle || "btn-primary");

      // Click outside the card to dismiss (treat as cancel).
      function onBackdropClick(e) { if (e.target === modal) finish(false); }

      function finish(result) {
        modal.classList.add("hidden");
        okBtn.removeEventListener("click", onOk);
        noBtn.removeEventListener("click", onNo);
        modal.removeEventListener("click", onBackdropClick);
        document.removeEventListener("keydown", onKey);
        resolve(result);
      }
      function onOk() { finish(true); }
      function onNo() { finish(false); }
      function onKey(e) {
        if (e.key === "Escape") finish(false);
        if (e.key === "Enter")  finish(true);
      }

      okBtn.addEventListener("click", onOk);
      noBtn.addEventListener("click", onNo);
      modal.addEventListener("click", onBackdropClick);
      document.addEventListener("keydown", onKey);

      modal.classList.remove("hidden");
      setTimeout(function () { okBtn.focus(); }, 30);
    });
  }

  // Pick a danger style for buttons that already look destructive.
  function inferOkStyle(el) {
    const cls = el.className || "";
    if (cls.indexOf("btn-danger") !== -1)  return "btn-danger";
    if (cls.indexOf("btn-warning") !== -1) return "btn-warning";
    return "btn-primary";
  }

  function handleClick(e) {
    const trigger = e.target.closest("[data-confirm]");
    if (!trigger) return;
    // We've already confirmed this exact element — let the action through.
    if (trigger.dataset.confirmed === "true") {
      // Clear it so a second click re-prompts (the form may have been replaced
      // after submit, but if we're here on the same element, gate it again).
      delete trigger.dataset.confirmed;
      return;
    }

    e.preventDefault();
    e.stopPropagation();

    showConfirm({
      title:       trigger.dataset.confirmTitle  || "Are you sure?",
      message:     trigger.dataset.confirm       || "",
      okLabel:     trigger.dataset.confirmOk     || "Confirm",
      cancelLabel: trigger.dataset.confirmCancel || "Cancel",
      okStyle:     inferOkStyle(trigger),
    }).then(function (ok) {
      if (!ok) return;
      // Mark as confirmed and re-trigger the original action.
      trigger.dataset.confirmed = "true";
      if (trigger.tagName === "BUTTON" && trigger.form) {
        // Submit the parent form, honoring the button's type/value.
        trigger.form.submit();
      } else if (trigger.tagName === "A" && trigger.href) {
        window.location = trigger.href;
      } else {
        trigger.click();
      }
    });
  }

  // Capture-phase so we intercept before native submit handlers run.
  document.addEventListener("click", handleClick, true);
})();
