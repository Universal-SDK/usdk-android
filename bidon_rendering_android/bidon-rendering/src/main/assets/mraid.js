// mraid.js — MRAID 2.0/3.0 JavaScript bridge
// Injected by MraidWebView before creative markup loads.
// Native calls go through window._mraidBridge (MraidJsBridge.kt).
// Creative calls go through window.mraid (this file).

(function (window) {
    "use strict";

    // ──────────────────────────────────────────
    // Internal state
    // ──────────────────────────────────────────

    var _state = "loading";
    var _placementType = "inline";
    var _isViewable = false;
    var _isReady = false;

    var _expandProperties = {
        width: window.screen.width,
        height: window.screen.height,
        useCustomClose: false,
        isModal: true
    };

    var _resizeProperties = {
        width: 0,
        height: 0,
        offsetX: 0,
        offsetY: 0,
        customClosePosition: "top-right",
        allowOffscreen: true
    };

    var _listeners = {};   // { eventName: [fn, fn, ...] }

    // ──────────────────────────────────────────
    // Event system
    // ──────────────────────────────────────────

    function addEventListener(event, listener) {
        if (typeof listener !== "function") return;
        if (!_listeners[event]) _listeners[event] = [];
        _listeners[event].push(listener);
    }

    function removeEventListener(event, listener) {
        if (!_listeners[event]) return;
        _listeners[event] = _listeners[event].filter(function (fn) {
            return fn !== listener;
        });
    }

    function fireEvent(event) {
        var args = Array.prototype.slice.call(arguments, 1);
        var fns = _listeners[event] || [];
        fns.forEach(function (fn) {
            try { fn.apply(null, args); } catch (e) { /* ignore creative errors */ }
        });
    }

    // ──────────────────────────────────────────
    // Events fired by native (MraidWebView.kt)
    // ──────────────────────────────────────────

    function fireReadyEvent() {
        _isReady = true;
        _state = "default";
        fireEvent("ready");
    }

    function fireStateChangeEvent(newState) {
        _state = newState;
        fireEvent("stateChange", newState);
    }

    function fireViewableChangeEvent(isViewable) {
        _isViewable = isViewable;
        fireEvent("viewableChange", isViewable);
    }

    function fireSizeChangeEvent(width, height) {
        fireEvent("sizeChange", width, height);
    }

    function fireErrorEvent(message, action) {
        fireEvent("error", message, action);
    }

    // ──────────────────────────────────────────
    // MRAID API — getters
    // ──────────────────────────────────────────

    function getVersion() { return "2.0"; }

    function getState() { return _state; }

    function getPlacementType() { return _placementType; }

    function isViewable() { return _isViewable; }

    function isReady() { return _isReady; }

    // Links to sms: and tel: open the device's messaging and phone apps when it has them, as any link opens its app.
    var _supported = { sms: true, tel: true, calendar: false, storePicture: false, inlineVideo: true };

    function supports(feature) { return _supported[feature] === true; }

    function getExpandProperties() {
        return JSON.parse(JSON.stringify(_expandProperties));
    }

    function getResizeProperties() {
        return JSON.parse(JSON.stringify(_resizeProperties));
    }

    function getScreenSize() {
        return {
            width: window.screen.width,
            height: window.screen.height
        };
    }

    function getMaxSize() {
        return {
            width: window.screen.width,
            height: window.screen.height
        };
    }

    function getCurrentPosition() {
        return { x: 0, y: 0, width: window.innerWidth, height: window.innerHeight };
    }

    function getDefaultPosition() {
        return { x: 0, y: 0, width: window.innerWidth, height: window.innerHeight };
    }

    // ──────────────────────────────────────────
    // MRAID API — setters
    // ──────────────────────────────────────────

    function setExpandProperties(properties) {
        if (properties.width !== undefined) _expandProperties.width = properties.width;
        if (properties.height !== undefined) _expandProperties.height = properties.height;
        if (properties.useCustomClose !== undefined) _expandProperties.useCustomClose = properties.useCustomClose;
    }

    function setResizeProperties(properties) {
        _resizeProperties = Object.assign(_resizeProperties, properties);
    }

    function useCustomClose(shouldUseCustomClose) {
        _expandProperties.useCustomClose = shouldUseCustomClose;
    }

    // ──────────────────────────────────────────
    // MRAID API — actions
    // ──────────────────────────────────────────

    // A link leaves the ad the way every other link in the creative does, as a navigation. ClickHandler opens it,
    // and counts the click, only when a tap started it; the bridge could not tell, since the creative's script may
    // call it at any time.
    function openLink(url, action) {
        if (!url) { fireErrorEvent("URL required", action); return; }
        // The creative's own page rather than a link: it would replace the creative in place. Only to tell the
        // creative; it could navigate there itself.
        if (/^\s*(about|javascript):/i.test(url)) { fireErrorEvent("Not a link", action); return; }
        // Usually tells the creative why nothing opens; the decision is ClickHandler's, out of the creative's reach. A
        // second link after one tap passes here and is refused there.
        var activation = window.navigator.userActivation;
        if (activation && !activation.isActive) { fireErrorEvent("Opens only after a tap on the ad", action); return; }
        // A link the page cannot resolve throws, and the creative's own handler would stop with it.
        try { window.location.href = url; }
        catch (e) { fireErrorEvent(e.message, action); }
    }

    function open(url) { openLink(url, "open"); }

    function expand(url) {
        if (_state !== "default") {
            fireErrorEvent("Can only expand from default state", "expand");
            return;
        }
        try {
            if (url) {
                window._mraidBridge.expand(url);
            } else {
                window._mraidBridge.expand();
            }
        } catch (e) { fireErrorEvent(e.message, "expand"); }
    }

    function close() {
        if (_state === "loading") {
            fireErrorEvent("Cannot close in loading state", "close");
            return;
        }
        try { window._mraidBridge.close(); }
        catch (e) { fireErrorEvent(e.message, "close"); }
    }

    function resize() {
        if (_state !== "default" && _state !== "resized") {
            fireErrorEvent("Can only resize from default or resized state", "resize");
            return;
        }
        var p = _resizeProperties;
        if (!p.width || !p.height) {
            fireErrorEvent("resizeProperties must set width and height", "resize");
            return;
        }
        try {
            window._mraidBridge.resize(p.width, p.height, p.offsetX || 0, p.offsetY || 0);
        } catch (e) { fireErrorEvent(e.message, "resize"); }
    }

    // Played by whatever opens the link: a browser, or an app that accepts such links from the web.
    function playVideo(url) { openLink(url, "playVideo"); }

    // Opened as a link, not saved to the device, which is why supports("storePicture") is false.
    function storePicture(url) { openLink(url, "storePicture"); }

    // Removed from the standard in MRAID 3.0, and not supported here.
    function createCalendarEvent() {
        fireErrorEvent("Calendar events are not supported", "createCalendarEvent");
    }

    // ──────────────────────────────────────────
    // Internal: native sets placement type on inject
    // ──────────────────────────────────────────

    function _setPlacementType(type) {
        _placementType = type;
    }

    // ──────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────

    window.mraid = {
        // Getters
        getVersion: getVersion,
        getState: getState,
        getPlacementType: getPlacementType,
        isViewable: isViewable,
        isReady: isReady,
        supports: supports,
        getExpandProperties: getExpandProperties,
        getResizeProperties: getResizeProperties,
        getScreenSize: getScreenSize,
        getMaxSize: getMaxSize,
        getCurrentPosition: getCurrentPosition,
        getDefaultPosition: getDefaultPosition,

        // Setters
        setExpandProperties: setExpandProperties,
        setResizeProperties: setResizeProperties,
        useCustomClose: useCustomClose,

        // Actions
        open: open,
        expand: expand,
        close: close,
        resize: resize,
        playVideo: playVideo,
        storePicture: storePicture,
        createCalendarEvent: createCalendarEvent,

        // Events
        addEventListener: addEventListener,
        removeEventListener: removeEventListener,

        // Native → JS (called by MraidWebView.kt)
        fireReadyEvent: fireReadyEvent,
        fireStateChangeEvent: fireStateChangeEvent,
        fireViewableChangeEvent: fireViewableChangeEvent,
        fireSizeChangeEvent: fireSizeChangeEvent,
        fireErrorEvent: fireErrorEvent,

        // Internal
        _setPlacementType: _setPlacementType
    };

}(window));