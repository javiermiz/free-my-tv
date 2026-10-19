(() => {
  const reduce = matchMedia("(prefers-reduced-motion: reduce)").matches;
  const pt = document.documentElement.lang.startsWith("pt");

  // The PT/EN switch is remembered, so the automatic choice in <head> doesn't undo it.
  document.querySelectorAll("[data-lang]").forEach((a) =>
    a.addEventListener("click", () => {
      try {
        localStorage.setItem("lang", a.dataset.lang);
      } catch {}
    })
  );

  // Navigation gets a shadow once the page scrolls.
  const nav = document.getElementById("nav");
  const onScroll = () => nav.classList.toggle("scrolled", scrollY > 10);
  addEventListener("scroll", onScroll, { passive: true });
  onScroll();

  // Reveal on scroll, siblings slightly staggered.
  const reveal = new IntersectionObserver(
    (entries) => {
      for (const e of entries) {
        if (!e.isIntersecting) continue;
        e.target.classList.add("in");
        reveal.unobserve(e.target);
      }
    },
    { rootMargin: "0px 0px -8% 0px", threshold: 0.12 }
  );
  document.querySelectorAll("[data-reveal]").forEach((el) => {
    const siblings = [...el.parentElement.children].filter((c) => c.hasAttribute("data-reveal"));
    const i = siblings.indexOf(el);
    if (i > 0) el.style.setProperty("--d", Math.min(i, 6) * 0.08 + "s");
    reveal.observe(el);
  });

  // Videos load and play only while on screen.
  const videos = new IntersectionObserver(
    (entries) => {
      for (const e of entries) {
        const v = e.target;
        if (e.isIntersecting) {
          if (!v.getAttribute("src") && v.dataset.src) v.src = v.dataset.src;
          if (!reduce) v.play().catch(() => {});
        } else {
          v.pause();
        }
      }
    },
    { threshold: 0.25 }
  );
  document.querySelectorAll("video[data-src]").forEach((v) => videos.observe(v));

  // The hero TV leans toward the pointer.
  const heroTv = document.getElementById("heroTv");
  if (heroTv && !reduce && matchMedia("(pointer: fine)").matches) {
    const stage = heroTv.parentElement;
    addEventListener(
      "pointermove",
      (ev) => {
        const r = stage.getBoundingClientRect();
        const x = (ev.clientX - (r.left + r.width / 2)) / innerWidth;
        const y = (ev.clientY - (r.top + r.height / 2)) / innerHeight;
        heroTv.style.setProperty("--ry", (-12 + x * 12).toFixed(2) + "deg");
        heroTv.style.setProperty("--rx", (4 - y * 6).toFixed(2) + "deg");
      },
      { passive: true }
    );
  }

  // Before / after: drag, arrow keys, and one automatic sweep the first time it shows up.
  const ba = document.getElementById("ba");
  if (ba) {
    let x = 50;
    let dragging = false;
    let touched = false;
    const set = (v) => {
      x = Math.max(0, Math.min(100, v));
      ba.style.setProperty("--x", x + "%");
      ba.setAttribute("aria-valuenow", String(Math.round(x)));
    };
    const fromEvent = (ev) => {
      const r = ba.getBoundingClientRect();
      set(((ev.clientX - r.left) / r.width) * 100);
    };
    ba.addEventListener("pointerdown", (ev) => {
      dragging = true;
      touched = true;
      ba.setPointerCapture(ev.pointerId);
      fromEvent(ev);
    });
    ba.addEventListener("pointermove", (ev) => {
      if (dragging) fromEvent(ev);
    });
    const stop = () => (dragging = false);
    ba.addEventListener("pointerup", stop);
    ba.addEventListener("pointercancel", stop);
    ba.addEventListener("keydown", (ev) => {
      if (ev.key === "ArrowLeft" || ev.key === "ArrowRight") {
        touched = true;
        set(x + (ev.key === "ArrowLeft" ? -5 : 5));
        ev.preventDefault();
      }
    });
    set(50);
    const ease = (t) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2);
    const sweep = () => {
      const keys = [
        [0, 50],
        [1000, 88],
        [2200, 12],
        [3200, 50],
      ];
      const t0 = performance.now();
      const step = (now) => {
        if (touched) return;
        const t = now - t0;
        let i = 0;
        while (i < keys.length - 2 && t > keys[i + 1][0]) i++;
        const [a, va] = keys[i];
        const [b, vb] = keys[i + 1];
        set(va + (vb - va) * ease(Math.min(1, Math.max(0, (t - a) / (b - a)))));
        if (t < keys[keys.length - 1][0]) requestAnimationFrame(step);
      };
      requestAnimationFrame(step);
    };
    if (!reduce) {
      const once = new IntersectionObserver(
        (entries) => {
          if (!entries[0].isIntersecting) return;
          once.disconnect();
          setTimeout(sweep, 500);
        },
        { threshold: 0.6 }
      );
      once.observe(ba);
    }
  }

  // Sunset: scrolling through the section turns day into night.
  const sunset = document.getElementById("sunset");
  if (sunset) {
    const sticky = sunset.querySelector(".sticky");
    const clock = document.getElementById("sunClock");
    const mix = (a, b, t) => a.map((v, i) => Math.round(v + (b[i] - v) * t));
    const rgb = (c) => `rgb(${c.join(",")})`;
    let last = -1;
    let queued = false;
    const update = () => {
      queued = false;
      const r = sunset.getBoundingClientRect();
      const total = Math.max(1, r.height - innerHeight);
      const p = Math.max(0, Math.min(1, -r.top / total));
      const e = p < 0.5 ? 2 * p * p : 1 - Math.pow(-2 * p + 2, 2) / 2;
      if (Math.abs(e - last) < 0.002) return;
      last = e;
      sunset.style.setProperty("--p", e.toFixed(3));
      sticky.style.setProperty("--sunset-bg", rgb(mix([245, 245, 247], [11, 11, 20], e)));
      sticky.style.setProperty("--sunset-ink", rgb(mix([29, 29, 31], [245, 245, 247], e)));
      sticky.style.setProperty("--sunset-muted", rgb(mix([110, 110, 115], [168, 168, 178], e)));
      sticky.style.setProperty("--sunset-faint", rgb(mix([142, 142, 147], [130, 132, 160], e)));
      const minutes = 17 * 60 + 20 + Math.round(e * 50); // 5:20 PM -> 6:10 PM
      const mm = String(minutes % 60).padStart(2, "0");
      clock.textContent = pt ? `${Math.floor(minutes / 60)}:${mm}` : `${Math.floor(minutes / 60) - 12}:${mm} PM`;
    };
    const queue = () => {
      if (queued) return;
      queued = true;
      requestAnimationFrame(update);
    };
    addEventListener("scroll", queue, { passive: true });
    addEventListener("resize", queue);
    update();
  }

  // Numbers count up when they come into view.
  const counters = new IntersectionObserver(
    (entries) => {
      for (const e of entries) {
        if (!e.isIntersecting) continue;
        counters.unobserve(e.target);
        const el = e.target;
        const to = Number(el.dataset.count);
        if (reduce) {
          el.textContent = String(to);
          continue;
        }
        const t0 = performance.now();
        const step = (now) => {
          const f = Math.min(1, (now - t0) / 1400);
          el.textContent = String(Math.round(to * (1 - Math.pow(1 - f, 3))));
          if (f < 1) requestAnimationFrame(step);
        };
        requestAnimationFrame(step);
      }
    },
    { threshold: 0.6 }
  );
  document.querySelectorAll("[data-count]").forEach((el) => counters.observe(el));

  // The frame-time line of the 60 FPS meter.
  const spark = document.getElementById("spark");
  if (spark) {
    const pts = Array.from({ length: 30 }, () => 12);
    const draw = () => spark.setAttribute("points", pts.map((y, i) => `${((i * 120) / 29).toFixed(1)},${y.toFixed(1)}`).join(" "));
    draw();
    if (!reduce) {
      setInterval(() => {
        pts.shift();
        pts.push(12 + (Math.random() - 0.5) * 3);
        draw();
      }, 120);
    }
  }

  // Install tabs.
  const tabs = [...document.querySelectorAll('[role="tab"]')];
  const select = (tab) => {
    for (const t of tabs) {
      const on = t === tab;
      t.setAttribute("aria-selected", String(on));
      t.tabIndex = on ? 0 : -1;
      document.getElementById(t.getAttribute("aria-controls")).hidden = !on;
    }
  };
  tabs.forEach((t, i) => {
    t.addEventListener("click", () => select(t));
    t.addEventListener("keydown", (ev) => {
      if (ev.key !== "ArrowLeft" && ev.key !== "ArrowRight") return;
      const next = tabs[(i + (ev.key === "ArrowRight" ? 1 : tabs.length - 1)) % tabs.length];
      select(next);
      next.focus();
    });
  });

  // Copy buttons.
  document.querySelectorAll(".copy-btn").forEach((b) =>
    b.addEventListener("click", async () => {
      const text = b.previousElementSibling.textContent.trim();
      try {
        await navigator.clipboard.writeText(text);
      } catch {
        const range = document.createRange();
        range.selectNodeContents(b.previousElementSibling);
        const sel = getSelection();
        sel.removeAllRanges();
        sel.addRange(range);
      }
      b.textContent = pt ? "Copiado" : "Copied";
      b.classList.add("done");
      setTimeout(() => {
        b.textContent = pt ? "Copiar" : "Copy";
        b.classList.remove("done");
      }, 1600);
    })
  );

  // The film.
  const film = document.getElementById("film");
  const filmVideo = film.querySelector("video");
  document.querySelectorAll("[data-film]").forEach((b) =>
    b.addEventListener("click", () => {
      film.showModal();
      filmVideo.play().catch(() => {});
    })
  );
  film.querySelector(".close").addEventListener("click", () => film.close());
  film.addEventListener("click", (e) => {
    if (e.target === film) film.close();
  });
  film.addEventListener("close", () => filmVideo.pause());
})();
