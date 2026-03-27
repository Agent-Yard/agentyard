(function () {
  const slides = Array.from(document.querySelectorAll(".slide"));
  const counter = document.getElementById("slide-counter");
  const progressFill = document.getElementById("progress-fill");
  const overlay = document.getElementById("diagram-overlay");
  const overlayBody = document.getElementById("diagram-overlay-body");
  const overlayTitle = document.getElementById("diagram-overlay-title");
  const overlayClose = document.getElementById("diagram-overlay-close");
  const isCompact = () => window.innerWidth <= 1100;
  let current = 0;
  let overlayOpen = false;
  let dragState = null;

  function render(index) {
    current = Math.max(0, Math.min(index, slides.length - 1));

    slides.forEach((slide, idx) => {
      slide.classList.toggle("active", idx === current || isCompact());
    });

    if (counter) {
      counter.textContent = `${current + 1} / ${slides.length}`;
    }

    if (progressFill) {
      const percent = ((current + 1) / slides.length) * 100;
      progressFill.style.width = `${percent}%`;
    }

    if (!isCompact()) {
      const title = slides[current]?.dataset.title;
      if (title) {
        document.title = `Lynxus 项目汇报 - ${title}`;
      }
    }
  }

  function next() {
    render(current + 1);
  }

  function prev() {
    render(current - 1);
  }

  document.addEventListener("keydown", (event) => {
    if (overlayOpen) {
      if (event.key === "Escape") {
        event.preventDefault();
        closeOverlay();
      }
      return;
    }
    if (isCompact()) {
      return;
    }
    if (event.key === "ArrowRight" || event.key === "PageDown" || event.key === " ") {
      event.preventDefault();
      next();
    } else if (event.key === "ArrowLeft" || event.key === "PageUp") {
      event.preventDefault();
      prev();
    } else if (event.key === "Home") {
      event.preventDefault();
      render(0);
    } else if (event.key === "End") {
      event.preventDefault();
      render(slides.length - 1);
    }
  });

  slides.forEach((slide, idx) => {
    slide.addEventListener("click", () => {
      if (overlayOpen) {
        return;
      }
      if (isCompact()) {
        return;
      }
      render(idx);
    });
  });

  window.addEventListener("resize", () => render(current));

  function openOverlay(sourceCard) {
    if (!overlay || !overlayBody || !overlayTitle) {
      return;
    }
    const rendered = sourceCard.querySelector(".mermaid");
    if (!rendered) {
      return;
    }
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
    if (!overlay || !overlayBody) {
      return;
    }
    overlay.classList.remove("open");
    overlay.setAttribute("aria-hidden", "true");
    overlayBody.innerHTML = "";
    overlayBody.classList.remove("dragging");
    overlayOpen = false;
    dragState = null;
    document.body.style.overflow = "";
  }

  document.querySelectorAll(".mermaid-card.zoomable").forEach((card) => {
    card.addEventListener("click", (event) => {
      if (isCompact()) {
        return;
      }
      event.stopPropagation();
      openOverlay(card);
    });
  });

  if (overlayClose) {
    overlayClose.addEventListener("click", closeOverlay);
  }

  if (overlay) {
    overlay.addEventListener("click", (event) => {
      if (event.target === overlay) {
        closeOverlay();
      }
    });
  }

  if (overlayBody) {
    overlayBody.addEventListener("mousedown", (event) => {
      if (!overlayOpen) {
        return;
      }
      dragState = {
        startX: event.clientX,
        startY: event.clientY,
        scrollLeft: overlayBody.scrollLeft,
        scrollTop: overlayBody.scrollTop,
      };
      overlayBody.classList.add("dragging");
    });

    overlayBody.addEventListener("mousemove", (event) => {
      if (!dragState) {
        return;
      }
      const deltaX = event.clientX - dragState.startX;
      const deltaY = event.clientY - dragState.startY;
      overlayBody.scrollLeft = dragState.scrollLeft - deltaX;
      overlayBody.scrollTop = dragState.scrollTop - deltaY;
    });

    const stopDragging = () => {
      dragState = null;
      overlayBody.classList.remove("dragging");
    };

    overlayBody.addEventListener("mouseup", stopDragging);
    overlayBody.addEventListener("mouseleave", stopDragging);
  }

  if (window.mermaid) {
    window.mermaid.initialize({
      startOnLoad: true,
      theme: "base",
      securityLevel: "loose",
      themeVariables: {
        primaryColor: "#e2f1ef",
        primaryTextColor: "#1f2330",
        primaryBorderColor: "#0f766e",
        lineColor: "#576074",
        secondaryColor: "#fff6e8",
        tertiaryColor: "#f9efe1",
        fontFamily: '"Source Han Sans SC", "PingFang SC", "Microsoft YaHei", sans-serif',
      },
      flowchart: {
        htmlLabels: true,
        curve: "basis",
      },
    });
  }

  render(0);
})();
