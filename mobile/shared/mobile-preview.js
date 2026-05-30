(function() {
  var titleEl = document.getElementById('title');
  var previewEl = document.getElementById('preview');
  var baseEl = document.getElementById('base-href');
  var openButtons = document.querySelectorAll('.open-file');
  var printButton = document.getElementById('print-file');
  var searchToggle = document.getElementById('search-toggle');
  var searchBar = document.getElementById('search-bar');
  var searchInput = document.getElementById('search-input');
  var searchCount = document.getElementById('search-count');
  var searchPrev = document.getElementById('search-prev');
  var searchNext = document.getElementById('search-next');
  var searchClose = document.getElementById('search-close');
  var recentSection = document.getElementById('recent-section');
  var recentList = document.getElementById('recent-list');
  var darkSheet = document.getElementById('hljs-dark');
  var lightSheet = document.getElementById('hljs-light');
  var loaded = { katex: false, mermaid: false };
  var searchHits = [];
  var currentHit = -1;
  var recentItems = [];

  if (window.marked && window.marked.setOptions) {
    window.marked.setOptions({
      gfm: true,
      breaks: false,
      mangle: false,
      headerIds: false,
      highlight: function(code, lang) {
        if (!window.hljs) return code;
        if (lang && window.hljs.getLanguage(lang)) {
          return window.hljs.highlight(code, { language: lang }).value;
        }
        return window.hljs.highlightAuto(code).value;
      }
    });
  }

  function applyTheme(e) {
    var dark = !!e.matches;
    lightSheet.media = dark ? 'not all' : '';
    darkSheet.media = dark ? '' : 'not all';
  }

  if (window.matchMedia) {
    var mq = window.matchMedia('(prefers-color-scheme: dark)');
    applyTheme(mq);
    if (mq.addEventListener) mq.addEventListener('change', applyTheme);
  }

  function hasUnescapedPair(text, open, close) {
    var pos = 0;
    while ((pos = text.indexOf(open, pos)) >= 0) {
      var body = pos + open.length;
      var end = text.indexOf(close, body);
      if (end > body) return true;
      pos = body;
    }
    return false;
  }

  function featureFlags(markdown) {
    return {
      math: hasUnescapedPair(markdown, '$$', '$$') ||
        hasUnescapedPair(markdown, '\\[', '\\]') ||
        hasUnescapedPair(markdown, '\\(', '\\)') ||
        /(^|[^\\])\$[^\s$][\s\S]*?[^\s\\]\$/.test(markdown),
      mermaid: /(^|\n)\s*(```|~~~)\s*mermaid(\s|\n|$)/i.test(markdown)
    };
  }

  function loadScript(src, key) {
    if (loaded[key]) return Promise.resolve();
    loaded[key] = true;
    return new Promise(function(resolve, reject) {
      var script = document.createElement('script');
      script.src = src;
      script.async = true;
      script.onload = resolve;
      script.onerror = reject;
      document.head.appendChild(script);
    });
  }

  function loadCss(href, id) {
    if (document.getElementById(id)) return;
    var link = document.createElement('link');
    link.id = id;
    link.rel = 'stylesheet';
    link.href = href;
    document.head.appendChild(link);
  }

  function idle(fn) {
    return (window.requestIdleCallback || function(cb) { return setTimeout(cb, 0); })(fn);
  }

  function enhance(flags) {
    if (window.__setFeatureFlags) window.__setFeatureFlags(flags.math, flags.mermaid);
    var tasks = [];
    if (flags.math && !window.katex) {
      loadCss('vendor/katex.inline.css', 'katex-css-link');
      tasks.push(loadScript('vendor/katex.min.js', 'katex'));
    }
    if (flags.mermaid && !window.mermaid) {
      tasks.push(loadScript('vendor/mermaid.min.js', 'mermaid'));
    }
    Promise.all(tasks).then(function() {
      if (window.__enhancePreview) window.__enhancePreview();
    });
  }

  function render(payload) {
    clearSearch();
    var markdown = payload && payload.markdown ? String(payload.markdown) : '';
    var name = payload && payload.name ? String(payload.name) : 'Untitled.md';
    var baseHref = payload && payload.baseHref ? String(payload.baseHref) : '';
    var flags = featureFlags(markdown);
    document.body.classList.remove('empty');
    titleEl.textContent = name;
    document.title = name + ' - MD Preview';
    if (baseHref) baseEl.setAttribute('href', baseHref);
    else baseEl.removeAttribute('href');
    previewEl.innerHTML = window.marked ? window.marked.parse(markdown) : markdown;
    idle(function() {
      if (window.hljs && window.hljs.highlightAll) window.hljs.highlightAll();
      enhance(flags);
    });
  }

  function sendNative(action, payload) {
    if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.mdPreview) {
      var message = payload || {};
      message.action = action;
      window.webkit.messageHandlers.mdPreview.postMessage(message);
      return true;
    }
    if (window.MDPreviewAndroid) {
      if (action === 'open' && window.MDPreviewAndroid.openFile) {
        window.MDPreviewAndroid.openFile();
        return true;
      }
      if (action === 'print' && window.MDPreviewAndroid.printDocument) {
        window.MDPreviewAndroid.printDocument();
        return true;
      }
      if (action === 'recent' && window.MDPreviewAndroid.getRecent) {
        window.MDPreviewAndroid.getRecent();
        return true;
      }
      if (action === 'openRecent' && window.MDPreviewAndroid.openRecent) {
        window.MDPreviewAndroid.openRecent(String(payload.id));
        return true;
      }
    }
    return false;
  }

  function clearSearch() {
    searchHits.forEach(function(mark) {
      var parent = mark.parentNode;
      if (!parent) return;
      parent.replaceChild(document.createTextNode(mark.textContent), mark);
      parent.normalize();
    });
    searchHits = [];
    currentHit = -1;
    updateSearchCount();
  }

  function updateSearchCount() {
    searchCount.textContent = searchHits.length ? (currentHit + 1) + '/' + searchHits.length : '0/0';
  }

  function selectHit(index) {
    if (!searchHits.length) {
      updateSearchCount();
      return;
    }
    if (currentHit >= 0 && searchHits[currentHit]) {
      searchHits[currentHit].classList.remove('current');
    }
    currentHit = (index + searchHits.length) % searchHits.length;
    var hit = searchHits[currentHit];
    hit.classList.add('current');
    hit.scrollIntoView({ block: 'center', inline: 'nearest' });
    updateSearchCount();
  }

  function runSearch(query) {
    clearSearch();
    query = String(query || '').trim();
    if (!query) return;
    var needle = query.toLowerCase();
    var walker = document.createTreeWalker(previewEl, NodeFilter.SHOW_TEXT, {
      acceptNode: function(node) {
        if (!node.nodeValue || node.nodeValue.toLowerCase().indexOf(needle) < 0) {
          return NodeFilter.FILTER_REJECT;
        }
        var parent = node.parentElement;
        if (!parent || parent.closest('script,style,svg,mark.search-hit,.katex,.mdp-mermaid')) {
          return NodeFilter.FILTER_REJECT;
        }
        return NodeFilter.FILTER_ACCEPT;
      }
    });
    var nodes = [];
    while (walker.nextNode()) nodes.push(walker.currentNode);
    nodes.forEach(function(node) {
      var text = node.nodeValue;
      var lower = text.toLowerCase();
      var fragment = document.createDocumentFragment();
      var start = 0;
      var index;
      while ((index = lower.indexOf(needle, start)) >= 0) {
        if (index > start) fragment.appendChild(document.createTextNode(text.slice(start, index)));
        var mark = document.createElement('mark');
        mark.className = 'search-hit';
        mark.textContent = text.slice(index, index + query.length);
        searchHits.push(mark);
        fragment.appendChild(mark);
        start = index + query.length;
      }
      if (start < text.length) fragment.appendChild(document.createTextNode(text.slice(start)));
      node.parentNode.replaceChild(fragment, node);
    });
    selectHit(0);
  }

  function renderRecent(items) {
    recentItems = Array.isArray(items) ? items.slice(0, 8) : [];
    recentList.innerHTML = '';
    recentSection.hidden = recentItems.length === 0;
    recentItems.forEach(function(item) {
      var button = document.createElement('button');
      button.className = 'recent-item';
      button.type = 'button';
      button.textContent = item.name || 'Untitled.md';
      button.addEventListener('click', function() {
        sendNative('openRecent', { id: item.id });
      });
      recentList.appendChild(button);
    });
  }

  document.addEventListener('click', function(event) {
    var link = event.target && event.target.closest ? event.target.closest('a[href]') : null;
    if (!link) return;
    var href = link.getAttribute('href') || '';
    if (/^\s*(javascript:|data:|vbscript:)/i.test(href)) {
      event.preventDefault();
      return;
    }
    if (!/^(https?:|mailto:)/i.test(href)) return;
    event.preventDefault();
    if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.mdPreview) {
      window.webkit.messageHandlers.mdPreview.postMessage({ action: 'openExternal', url: href });
      return;
    }
    if (window.MDPreviewAndroid && window.MDPreviewAndroid.openExternal) {
      window.MDPreviewAndroid.openExternal(href);
    }
  });

  openButtons.forEach(function(button) {
    button.addEventListener('click', function() {
      sendNative('open');
    });
  });

  printButton.addEventListener('click', function() {
    if (!sendNative('print')) window.print();
  });

  searchToggle.addEventListener('click', function() {
    searchBar.hidden = false;
    searchInput.focus();
  });

  searchInput.addEventListener('input', function() {
    runSearch(searchInput.value);
  });

  searchPrev.addEventListener('click', function() {
    selectHit(currentHit - 1);
  });

  searchNext.addEventListener('click', function() {
    selectHit(currentHit + 1);
  });

  searchClose.addEventListener('click', function() {
    searchBar.hidden = true;
    searchInput.value = '';
    clearSearch();
  });

  window.MDPreview = {
    render: render,
    setRecent: renderRecent,
    setEmpty: function() {
      clearSearch();
      document.body.classList.add('empty');
      titleEl.textContent = 'MD Preview';
      previewEl.innerHTML = '';
    }
  };

  sendNative('recent');
})();
