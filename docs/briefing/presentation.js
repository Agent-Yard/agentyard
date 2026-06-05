(function () {
  const slides = Array.from(document.querySelectorAll(".slide"));
  const counter = document.getElementById("slide-counter");
  const progressFill = document.getElementById("progress-fill");
  const overlay = document.getElementById("diagram-overlay");
  const overlayBody = document.getElementById("diagram-overlay-body");
  const overlayTitle = document.getElementById("diagram-overlay-title");
  const overlayClose = document.getElementById("diagram-overlay-close");
  const hint = document.querySelector(".hint");
  const isCompact = () => window.innerWidth <= 1100;

  let current = 0;
  let overlayOpen = false;
  let dragState = null;
  let transitioning = false;
  let hintHidden = false;

  /* ─── Dot navigation ─── */
  const dotsContainer = document.getElementById("slide-dots");
  const dots = [];
  if (dotsContainer) {
    slides.forEach((_, i) => {
      const dot = document.createElement("button");
      dot.className = "slide-dot";
      dot.setAttribute("aria-label", `Slide ${i + 1}`);
      dot.addEventListener("click", () => goTo(i));
      dotsContainer.appendChild(dot);
      dots.push(dot);
    });
  }

  function render(index, direction) {
    if (transitioning || isCompact()) {
      if (isCompact()) {
        current = index;
        slides.forEach((s) => s.classList.add("active"));
      }
      return;
    }

    const next = Math.max(0, Math.min(index, slides.length - 1));
    if (next === current) return;

    const dir = direction || (next > current ? "forward" : "backward");
    transitioning = true;

    const leaving = slides[current];
    const entering = slides[next];

    // Reset classes
    slides.forEach((s) => {
      s.classList.remove("active", "leaving", "backward");
    });

    // Set direction
    if (dir === "backward") {
      entering.classList.add("backward");
      leaving.classList.add("backward");
    }

    // Trigger leaving animation
    leaving.classList.add("leaving");

    // Trigger entering animation
    requestAnimationFrame(() => {
      entering.classList.add("active");
    });

    current = next;
    updateMeta();

    // Clear transitioning lock
    setTimeout(() => {
      transitioning = false;
      leaving.classList.remove("leaving", "backward");
    }, 400);

    // Hide hint after first navigation
    if (!hintHidden && hint) {
      hintHidden = true;
      hint.classList.add("faded");
    }
  }

  function goTo(index) {
    if (index === current) return;
    render(index, index > current ? "forward" : "backward");
  }

  function next() {
    if (current < slides.length - 1) render(current + 1, "forward");
  }

  function prev() {
    if (current > 0) render(current - 1, "backward");
  }

  function updateMeta() {
    if (counter) {
      counter.textContent = `${current + 1} / ${slides.length}`;
    }

    if (progressFill) {
      const pct = ((current + 1) / slides.length) * 100;
      progressFill.style.width = `${pct}%`;
    }

    dots.forEach((dot, i) => {
      dot.classList.toggle("active", i === current);
    });

    if (!isCompact()) {
      const title = slides[current]?.dataset.title;
      if (title) {
        document.title = `AgentYard 项目汇报 — ${title}`;
      }
    }
  }

  /* ─── Keyboard navigation ─── */
  document.addEventListener("keydown", (e) => {
    if (overlayOpen) {
      if (e.key === "Escape") {
        e.preventDefault();
        closeOverlay();
      }
      return;
    }
    if (isCompact()) return;

    switch (e.key) {
      case "ArrowRight":
      case "PageDown":
      case " ":
        e.preventDefault();
        next();
        break;
      case "ArrowLeft":
      case "PageUp":
        e.preventDefault();
        prev();
        break;
      case "Home":
        e.preventDefault();
        goTo(0);
        break;
      case "End":
        e.preventDefault();
        goTo(slides.length - 1);
        break;
    }
  });

  /* ─── Touch / swipe ─── */
  let touchStart = null;
  document.addEventListener("touchstart", (e) => {
    if (isCompact() || overlayOpen) return;
    touchStart = { x: e.touches[0].clientX, y: e.touches[0].clientY };
  }, { passive: true });

  document.addEventListener("touchend", (e) => {
    if (!touchStart || isCompact() || overlayOpen) return;
    const dx = e.changedTouches[0].clientX - touchStart.x;
    const dy = e.changedTouches[0].clientY - touchStart.y;
    touchStart = null;
    if (Math.abs(dx) < 50 || Math.abs(dx) < Math.abs(dy)) return;
    if (dx < 0) next();
    else prev();
  }, { passive: true });

  /* ─── Click to navigate ─── */
  slides.forEach((slide, idx) => {
    slide.addEventListener("click", () => {
      if (overlayOpen || isCompact()) return;
      // Don't navigate on click - it interferes with content interaction
    });
  });

  /* ─── Resize handler ─── */
  window.addEventListener("resize", () => {
    if (isCompact()) {
      slides.forEach((s) => {
        s.classList.remove("leaving", "backward");
        s.classList.add("active");
      });
    } else {
      slides.forEach((s, i) => {
        s.classList.remove("leaving", "backward");
        s.classList.toggle("active", i === current);
      });
    }
    updateMeta();
  });

  /* ─── Diagram overlay ─── */
  function openOverlay(sourceCard) {
    if (!overlay || !overlayBody || !overlayTitle) return;
    const rendered = sourceCard.querySelector(".mermaid");
    if (!rendered) return;

    overlayBody.innerHTML = rendered.innerHTML;
    const svg = overlayBody.querySelector("svg");
    const scale = Number(sourceCard.dataset.zoomScale || "1.35");
    if (svg) {
      svg.style.width = `${Math.max(scale * 100, 100)}%`;
      svg.style.minWidth = `${Math.max(Math.round(1100 * scale), 1100)}px`;
    }

    overlayTitle.textContent = sourceCard.dataset.zoomLabel || "图表放大查看";
    overlay.classList.add("open");
    overlay.setAttribute("aria-hidden", "false");
    overlayOpen = true;
    document.body.style.overflow = "hidden";
    overlayBody.scrollLeft = 0;
    overlayBody.scrollTop = 0;
  }

  function closeOverlay() {
    if (!overlay || !overlayBody) return;
    overlay.classList.remove("open");
    overlay.setAttribute("aria-hidden", "true");
    overlayBody.innerHTML = "";
    overlayBody.classList.remove("dragging");
    overlayOpen = false;
    dragState = null;
    document.body.style.overflow = "";
  }

  document.querySelectorAll(".mermaid-card.zoomable").forEach((card) => {
    card.addEventListener("click", (e) => {
      if (isCompact()) return;
      e.stopPropagation();
      openOverlay(card);
    });
  });

  if (overlayClose) overlayClose.addEventListener("click", closeOverlay);

  if (overlay) {
    overlay.addEventListener("click", (e) => {
      if (e.target === overlay) closeOverlay();
    });
  }

  if (overlayBody) {
    overlayBody.addEventListener("mousedown", (e) => {
      if (!overlayOpen) return;
      dragState = {
        startX: e.clientX,
        startY: e.clientY,
        scrollLeft: overlayBody.scrollLeft,
        scrollTop: overlayBody.scrollTop,
      };
      overlayBody.classList.add("dragging");
    });

    overlayBody.addEventListener("mousemove", (e) => {
      if (!dragState) return;
      overlayBody.scrollLeft = dragState.scrollLeft - (e.clientX - dragState.startX);
      overlayBody.scrollTop = dragState.scrollTop - (e.clientY - dragState.startY);
    });

    const stopDrag = () => {
      dragState = null;
      overlayBody.classList.remove("dragging");
    };
    overlayBody.addEventListener("mouseup", stopDrag);
    overlayBody.addEventListener("mouseleave", stopDrag);
  }

  /* ─── Mermaid init ─── */
  if (window.mermaid) {
    window.mermaid.initialize({
      startOnLoad: true,
      theme: "base",
      securityLevel: "loose",
      themeVariables: {
        primaryColor: "#152544",
        primaryTextColor: "#f0f4fc",
        primaryBorderColor: "#f0c45c",
        lineColor: "#6cb4ff",
        secondaryColor: "#1a2e4c",
        tertiaryColor: "#1e1a2e",
        fontFamily: '"Noto Sans SC", "PingFang SC", "Microsoft YaHei", sans-serif',
      },
      flowchart: {
        htmlLabels: true,
        curve: "basis",
      },
    });
  }

  /* ─── Initial render ─── */
  slides[0]?.classList.add("active");
  updateMeta();
  if (isCompact()) {
    slides.forEach((s) => s.classList.add("active"));
  }
})();
