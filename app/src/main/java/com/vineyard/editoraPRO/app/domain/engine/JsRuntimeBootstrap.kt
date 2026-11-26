package com.vineyard.editoraPRO.app.domain.engine

/**
 * Pure JavaScript Canvas2D proxy, ImageData/Gradient wrappers, OfflineAudioContext shim,
 * and `runtime` object bindings executed inside QuickJS.
 */
object JsRuntimeBootstrap {

    val SCRIPT: String = """
        (function() {
          function _toHandle(obj) {
            if (!obj) return "";
            if (typeof obj === "string") return obj;
            if (obj.__handle) return obj.__handle;
            if (obj.canvas && obj.canvas.__handle) return obj.canvas.__handle;
            if (typeof obj.clipIndex === "number") return "__clip_" + obj.clipIndex;
            if (typeof obj === "number") return "__clip_" + Math.floor(obj);
            return "";
          }

          function _makeGradient(id) {
            return {
              __gradId: id,
              addColorStop: function(offset, color) {
                __host.gradAddStop(id, Number(offset) || 0, String(color || "#000"));
              }
            };
          }

          function _makeCtx(handle) {
            const state = {
              fillStyle: "#000000",
              strokeStyle: "#000000",
              lineWidth: 1,
              lineCap: "butt",
              lineJoin: "miter",
              miterLimit: 10,
              globalAlpha: 1,
              globalCompositeOperation: "source-over",
              font: "16px sans-serif",
              textAlign: "left",
              textBaseline: "alphabetic",
              shadowColor: "transparent",
              shadowBlur: 0,
              shadowOffsetX: 0,
              shadowOffsetY: 0,
              lineDashOffset: 0,
              _lineDash: []
            };

            const canvasRef = {
              __handle: handle,
              get width() { return __host.getWidth(handle); },
              set width(v) { __host.ensureCanvas(handle, Math.max(2, v | 0), this.height); },
              get height() { return __host.getHeight(handle); },
              set height(v) { __host.ensureCanvas(handle, this.width, Math.max(2, v | 0)); },
              getContext: function() { return ctx; }
            };

            const ctx = {
              canvas: canvasRef,
              get fillStyle() { return state.fillStyle; },
              set fillStyle(v) {
                state.fillStyle = v;
                if (v && v.__gradId) __host.setFillStyle(handle, String(v.__gradId));
                else __host.setFillStyle(handle, String(v ?? "#000000"));
              },
              get strokeStyle() { return state.strokeStyle; },
              set strokeStyle(v) {
                state.strokeStyle = v;
                if (v && v.__gradId) __host.setStrokeStyle(handle, String(v.__gradId));
                else __host.setStrokeStyle(handle, String(v ?? "#000000"));
              },
              get lineWidth() { return state.lineWidth; },
              set lineWidth(v) {
                state.lineWidth = Number(v) || 1;
                __host.setNumProp(handle, "lineWidth", state.lineWidth);
              },
              get lineCap() { return state.lineCap; },
              set lineCap(v) { state.lineCap = String(v || "butt"); __host.setStrProp(handle, "lineCap", state.lineCap); },
              get lineJoin() { return state.lineJoin; },
              set lineJoin(v) { state.lineJoin = String(v || "miter"); __host.setStrProp(handle, "lineJoin", state.lineJoin); },
              get miterLimit() { return state.miterLimit; },
              set miterLimit(v) { state.miterLimit = Number(v) || 10; __host.setNumProp(handle, "miterLimit", state.miterLimit); },
              get globalAlpha() { return state.globalAlpha; },
              set globalAlpha(v) {
                const a = Number(v);
                state.globalAlpha = Number.isFinite(a) ? Math.max(0, Math.min(1, a)) : 1;
                __host.setNumProp(handle, "globalAlpha", state.globalAlpha);
              },
              get globalCompositeOperation() { return state.globalCompositeOperation; },
              set globalCompositeOperation(v) {
                state.globalCompositeOperation = String(v || "source-over");
                __host.setStrProp(handle, "compositeOp", state.globalCompositeOperation);
              },
              get font() { return state.font; },
              set font(v) { state.font = String(v || "16px sans-serif"); __host.setStrProp(handle, "font", state.font); },
              get textAlign() { return state.textAlign; },
              set textAlign(v) { state.textAlign = String(v || "left"); __host.setStrProp(handle, "textAlign", state.textAlign); },
              get textBaseline() { return state.textBaseline; },
              set textBaseline(v) { state.textBaseline = String(v || "alphabetic"); __host.setStrProp(handle, "textBaseline", state.textBaseline); },
              get shadowColor() { return state.shadowColor; },
              set shadowColor(v) { state.shadowColor = String(v || "transparent"); __host.setStrProp(handle, "shadowColor", state.shadowColor); },
              get shadowBlur() { return state.shadowBlur; },
              set shadowBlur(v) { state.shadowBlur = Number(v) || 0; __host.setNumProp(handle, "shadowBlur", state.shadowBlur); },
              get shadowOffsetX() { return state.shadowOffsetX; },
              set shadowOffsetX(v) { state.shadowOffsetX = Number(v) || 0; __host.setNumProp(handle, "shadowOffsetX", state.shadowOffsetX); },
              get shadowOffsetY() { return state.shadowOffsetY; },
              set shadowOffsetY(v) { state.shadowOffsetY = Number(v) || 0; __host.setNumProp(handle, "shadowOffsetY", state.shadowOffsetY); },
              get lineDashOffset() { return state.lineDashOffset; },
              set lineDashOffset(v) { state.lineDashOffset = Number(v) || 0; __host.setNumProp(handle, "lineDashOffset", state.lineDashOffset); },
              set filter(v) {},
              get filter() { return "none"; },
              set imageSmoothingEnabled(v) {},
              get imageSmoothingEnabled() { return true; },
              set imageSmoothingQuality(v) {},
              get imageSmoothingQuality() { return "high"; },

              save: function() { __host.save(handle); },
              restore: function() { __host.restore(handle); },
              translate: function(x, y) { __host.translate(handle, Number(x) || 0, Number(y) || 0); },
              rotate: function(rad) { __host.rotate(handle, Number(rad) || 0); },
              scale: function(sx, sy) { __host.scale(handle, Number(sx) || 1, Number(sy) || 1); },
              transform: function(a, b, c, d, e, f) { __host.transform(handle, +a||1, +b||0, +c||0, +d||1, +e||0, +f||0); },
              setTransform: function(a, b, c, d, e, f) {
                if (typeof a === "object" && a) {
                  __host.setTransform(handle, +a.a||1, +a.b||0, +a.c||0, +a.d||1, +a.e||0, +a.f||0);
                } else {
                  __host.setTransform(handle, +a||1, +b||0, +c||0, +d||1, +e||0, +f||0);
                }
              },
              resetTransform: function() { __host.setTransform(handle, 1, 0, 0, 1, 0, 0); },

              setLineDash: function(arr) {
                const list = Array.isArray(arr) ? arr.map(Number) : [];
                state._lineDash = list;
                __host.setLineDash(handle, list);
              },
              getLineDash: function() { return state._lineDash.slice(); },

              clearRect: function(x, y, w, h) { __host.clearRect(handle, +x||0, +y||0, +w||0, +h||0); },
              fillRect: function(x, y, w, h) { __host.fillRect(handle, +x||0, +y||0, +w||0, +h||0); },
              strokeRect: function(x, y, w, h) { __host.strokeRect(handle, +x||0, +y||0, +w||0, +h||0); },

              beginPath: function() { __host.beginPath(handle); },
              closePath: function() { __host.closePath(handle); },
              moveTo: function(x, y) { __host.moveTo(handle, +x||0, +y||0); },
              lineTo: function(x, y) { __host.lineTo(handle, +x||0, +y||0); },
              quadraticCurveTo: function(cpx, cpy, x, y) { __host.quadraticCurveTo(handle, +cpx||0, +cpy||0, +x||0, +y||0); },
              bezierCurveTo: function(c1x, c1y, c2x, c2y, x, y) { __host.bezierCurveTo(handle, +c1x||0, +c1y||0, +c2x||0, +c2y||0, +x||0, +y||0); },
              rect: function(x, y, w, h) { __host.rect(handle, +x||0, +y||0, +w||0, +h||0); },
              roundRect: function(x, y, w, h, r) {
                const rr = Array.isArray(r) ? (+r[0] || 0) : (+r || 0);
                __host.roundRect(handle, +x||0, +y||0, +w||0, +h||0, rr);
              },
              arc: function(x, y, r, s, e, ccw) { __host.arc(handle, +x||0, +y||0, +r||0, +s||0, +e||0, !!ccw); },
              ellipse: function(x, y, rx, ry, rot, s, e, ccw) {
                __host.ellipse(handle, +x||0, +y||0, +rx||0, +ry||0, +rot||0, +s||0, +e||0, !!ccw);
              },
              fill: function(rule) { __host.fill(handle, typeof rule === "string" ? rule : "nonzero"); },
              stroke: function() { __host.stroke(handle); },
              clip: function() { __host.clip(handle); },

              fillText: function(txt, x, y, maxW) {
                __host.fillText(handle, String(txt ?? ""), +x||0, +y||0, maxW !== undefined ? (+maxW || 0) : -1);
              },
              strokeText: function(txt, x, y, maxW) {
                __host.strokeText(handle, String(txt ?? ""), +x||0, +y||0, maxW !== undefined ? (+maxW || 0) : -1);
              },
              measureText: function(txt) {
                return { width: __host.measureText(handle, String(txt ?? "")) };
              },

              createLinearGradient: function(x0, y0, x1, y1) {
                return _makeGradient(__host.gradLinear(+x0||0, +y0||0, +x1||0, +y1||0));
              },
              createRadialGradient: function(x0, y0, r0, x1, y1, r1) {
                return _makeGradient(__host.gradRadial(+x0||0, +y0||0, +r0||0, +x1||0, +y1||0, +r1||0));
              },

              drawImage: function(src, a1, a2, a3, a4, a5, a6, a7, a8) {
                const srcHandle = _toHandle(src);
                if (!srcHandle) return;
                if (arguments.length >= 9) {
                  __host.drawImage(handle, srcHandle, +a1||0, +a2||0, +a3||0, +a4||0, +a5||0, +a6||0, +a7||0, +a8||0);
                } else if (arguments.length >= 5) {
                  __host.drawImage(handle, srcHandle, 0, 0, -1, -1, +a1||0, +a2||0, +a3||0, +a4||0);
                } else {
                  __host.drawImage(handle, srcHandle, 0, 0, -1, -1, +a1||0, +a2||0, -1, -1);
                }
              },

              createImageData: function(w, h) {
                const iw = Math.max(1, (typeof w === "object" ? w.width : w) | 0);
                const ih = Math.max(1, (typeof w === "object" ? w.height : h) | 0);
                return { width: iw, height: ih, data: new Uint8ClampedArray(iw * ih * 4) };
              },
              getImageData: function(x, y, w, h) {
                const iw = Math.max(1, w | 0);
                const ih = Math.max(1, h | 0);
                const raw = __host.getImageData(handle, x | 0, y | 0, iw, ih);
                const data = new Uint8ClampedArray(raw.buffer, raw.byteOffset || 0, raw.byteLength || (iw * ih * 4));
                return { width: iw, height: ih, data: data };
              },
              putImageData: function(imgData, dx, dy) {
                if (!imgData || !imgData.data) return;
                const u8 = new Int8Array(imgData.data.buffer, imgData.data.byteOffset || 0, imgData.data.byteLength);
                __host.putImageData(handle, u8, dx | 0, dy | 0, imgData.width | 0, imgData.height | 0);
              }
            };
            return ctx;
          }

          function _wrapCanvasHandle(handle) {
            if (!handle) return null;
            let cachedCtx = null;
            return {
              __handle: handle,
              get width() { return __host.getWidth(handle); },
              set width(v) { __host.ensureCanvas(handle, Math.max(2, v | 0), this.height); },
              get height() { return __host.getHeight(handle); },
              set height(v) { __host.ensureCanvas(handle, this.width, Math.max(2, v | 0)); },
              getContext: function(type) {
                if (!cachedCtx) cachedCtx = _makeCtx(handle);
                return cachedCtx;
              }
            };
          }

          globalThis.__makeCtx = _makeCtx;
          globalThis.__wrapCanvas = _wrapCanvasHandle;

          globalThis.runtime = {
            getCanvas: function(key, w, h) {
              const handle = __host.getCanvas(String(key || "offscreen"), Math.max(2, w | 0), Math.max(2, h | 0));
              return _wrapCanvasHandle(handle);
            },
            seekClip: async function(media, timeSec) {
              await __hostAsync.seekClip(_toHandle(media), Number(timeSec) || 0);
            },
            getFrame: async function(media, timeSec) {
              const h = await __hostAsync.getFrame(_toHandle(media), Number(timeSec) || 0);
              return _wrapCanvasHandle(h);
            },
            drawCover: function(ctxOrHandle, media, x, y, w, h, zoom) {
              const dstH = _toHandle(ctxOrHandle);
              const srcH = _toHandle(media);
              if (!dstH || !srcH) return;
              __host.drawCover(dstH, srcH, +x||0, +y||0, +w||0, +h||0, zoom !== undefined ? (+zoom || 1) : 1);
            },
            runShader: async function(key, outW, outH, fragmentGlsl, texturesMap, uniformsMap) {
              const texHandles = {};
              if (texturesMap && typeof texturesMap === "object") {
                for (const k of Object.keys(texturesMap)) {
                  const th = _toHandle(texturesMap[k]);
                  if (th) texHandles[k] = th;
                }
              }
              const h = await __hostAsync.runShader(
                String(key || "fx"),
                Math.max(2, outW | 0),
                Math.max(2, outH | 0),
                String(fragmentGlsl || ""),
                texHandles,
                uniformsMap || {}
              );
              return _wrapCanvasHandle(h);
            },
            detectBoxes: async function(media, timeSec) {
              return await __hostAsync.detectBoxes(_toHandle(media), Number(timeSec) || 0);
            },
            getMediaPipeMask: async function(media, w, h) {
              const res = await __hostAsync.getMediaPipeMask(_toHandle(media), Math.max(2, w | 0), Math.max(2, h | 0));
              const raw = res.bytes;
              return {
                width: res.width,
                height: res.height,
                data: new Float32Array(raw.buffer, raw.byteOffset || 0, raw.byteLength / 4)
              };
            },
            segmentMediaPipe: async function(media, w, h, featherOrOpts) {
              let feather = 0.45, hardEdge = false, threshold = 0.5;
              if (typeof featherOrOpts === "number") {
                feather = featherOrOpts;
                if (featherOrOpts <= 0) hardEdge = true;
              } else if (featherOrOpts && typeof featherOrOpts === "object") {
                if (featherOrOpts.feather !== undefined) feather = Number(featherOrOpts.feather);
                if (featherOrOpts.hardEdge !== undefined) hardEdge = !!featherOrOpts.hardEdge;
                if (featherOrOpts.threshold !== undefined) threshold = Number(featherOrOpts.threshold);
              }
              const resH = await __hostAsync.segmentMediaPipe(_toHandle(media), Math.max(2, w | 0), Math.max(2, h | 0), feather, hardEdge, threshold);
              return _wrapCanvasHandle(resH);
            },
            getSamMask: async function(media, w, h, promptOpts) {
              const res = await __hostAsync.getSamMask(_toHandle(media), Math.max(2, w | 0), Math.max(2, h | 0), promptOpts || {});
              const raw = res.bytes;
              return {
                width: res.width,
                height: res.height,
                data: new Float32Array(raw.buffer, raw.byteOffset || 0, raw.byteLength / 4)
              };
            },
            segmentSam: async function(media, w, h, promptOpts, featherOrOpts) {
              let feather = 0.45, hardEdge = false, threshold = 0.5;
              if (typeof featherOrOpts === "number") {
                feather = featherOrOpts;
                if (featherOrOpts <= 0) hardEdge = true;
              } else if (featherOrOpts && typeof featherOrOpts === "object") {
                if (featherOrOpts.feather !== undefined) feather = Number(featherOrOpts.feather);
                if (featherOrOpts.hardEdge !== undefined) hardEdge = !!featherOrOpts.hardEdge;
                if (featherOrOpts.threshold !== undefined) threshold = Number(featherOrOpts.threshold);
              }
              const resH = await __hostAsync.segmentSam(_toHandle(media), Math.max(2, w | 0), Math.max(2, h | 0), promptOpts || {}, feather, hardEdge, threshold);
              return _wrapCanvasHandle(resH);
            },
            // Backwards-compatible aliases
            getSubjectMask: async function(media, w, h) {
              return await globalThis.runtime.getMediaPipeMask(media, w, h);
            },
            segmentSubject: async function(media, w, h, featherOrOpts) {
              return await globalThis.runtime.segmentMediaPipe(media, w, h, featherOrOpts);
            },
            chromaKey: function(media, w, h, targetRGB, tolerance, feather) {
              const rgb = Array.isArray(targetRGB) ? targetRGB.map(Number) : [0, 200, 0];
              const resH = __host.chromaKey(
                _toHandle(media),
                Math.max(2, w | 0),
                Math.max(2, h | 0),
                rgb[0] | 0,
                rgb[1] | 0,
                rgb[2] | 0,
                tolerance !== undefined ? Number(tolerance) : 90,
                feather !== undefined ? Number(feather) : 35
              );
              return _wrapCanvasHandle(resH);
            },
            extractAudio: function(clip) {
              return __host.extractAudio(_toHandle(clip));
            },
            setAudioBuffer: function(buf) {
              if (!buf) {
                __host.clearAudioBuffer();
                return;
              }
              if (typeof buf.__audioClipIdx === "number") {
                __host.setAudioFromClip(buf.__audioClipIdx | 0);
                return;
              }
              if (buf._channels && buf._channels.length > 0) {
                const ch0 = buf._channels[0];
                const u8 = new Int8Array(ch0.buffer, ch0.byteOffset || 0, ch0.byteLength);
                __host.setAudioSamples(u8, buf.sampleRate | 0, 1);
              }
            }
          };

          // Full procedural Web Audio shim supporting parameters, ramps, oscillators & buffer sources
          globalThis.OfflineAudioContext = function(channels, length, sampleRate) {
            const sr = sampleRate || 44100;
            const len = Math.max(1, length | 0);
            const bufData = [new Float32Array(len)];
            const scheduledOscs = [];
            const scheduledSources = [];

            function makeParam(initVal) {
              const p = {
                value: Number(initVal) || 0,
                _events: [],
                setValueAtTime: function(v, t) {
                  this._events.push({ type: "set", v: Number(v) || 0, t: Math.max(0, Number(t) || 0) });
                  return this;
                },
                linearRampToValueAtTime: function(v, t) {
                  this._events.push({ type: "linear", v: Number(v) || 0, t: Math.max(0, Number(t) || 0) });
                  return this;
                },
                exponentialRampToValueAtTime: function(v, t) {
                  this._events.push({ type: "exp", v: Math.max(0.00001, Number(v) || 0.00001), t: Math.max(0, Number(t) || 0) });
                  return this;
                },
                getValAt: function(timeSec) {
                  if (this._events.length === 0) return this.value;
                  const evs = this._events.slice().sort((a, b) => a.t - b.t);
                  if (timeSec <= evs[0].t) return evs[0].v;
                  for (let i = 0; i < evs.length - 1; i++) {
                    const e0 = evs[i];
                    const e1 = evs[i + 1];
                    if (timeSec >= e0.t && timeSec < e1.t) {
                      const dt = e1.t - e0.t;
                      if (dt <= 0.00001) return e1.v;
                      const frac = (timeSec - e0.t) / dt;
                      if (e1.type === "linear") {
                        return e0.v + (e1.v - e0.v) * frac;
                      } else if (e1.type === "exp") {
                        const safe0 = Math.max(0.00001, e0.v);
                        const safe1 = Math.max(0.00001, e1.v);
                        return safe0 * Math.pow(safe1 / safe0, frac);
                      }
                      return e0.v;
                    }
                  }
                  return evs[evs.length - 1].v;
                }
              };
              return p;
            }

            return {
              sampleRate: sr,
              destination: { _isDest: true },
              createBuffer: function(ch, l, s) {
                const arr = new Float32Array(Math.max(1, l | 0));
                return {
                  sampleRate: s || sr,
                  length: arr.length,
                  duration: arr.length / (s || sr),
                  numberOfChannels: ch || 1,
                  _channels: [arr],
                  getChannelData: () => arr
                };
              },
              createGain: function() {
                const node = {
                  _type: "gain",
                  gain: makeParam(1),
                  _outputs: [],
                  connect: function(dest) { if (dest) node._outputs.push(dest); return dest; }
                };
                return node;
              },
              createBiquadFilter: function() {
                const flt = {
                  _type: "filter",
                  type: "lowpass",
                  frequency: makeParam(1000),
                  Q: makeParam(1),
                  _outputs: [],
                  connect: function(dest) { if (dest) flt._outputs.push(dest); return dest; }
                };
                return flt;
              },
              createOscillator: function() {
                const osc = {
                  _type: "osc",
                  type: "sine",
                  frequency: makeParam(440),
                  _outputs: [],
                  _t0: 0,
                  _t1: 0.25,
                  connect: function(dest) { if (dest) osc._outputs.push(dest); return dest; },
                  start: function(t) { osc._t0 = Math.max(0, Number(t) || 0); scheduledOscs.push(osc); },
                  stop: function(t) { osc._t1 = Math.max(osc._t0 + 0.01, Number(t) || (osc._t0 + 0.25)); }
                };
                return osc;
              },
              createBufferSource: function() {
                const bs = {
                  _type: "bufferSource",
                  buffer: null,
                  _outputs: [],
                  _t0: 0,
                  _offset: 0,
                  _duration: -1,
                  connect: function(dest) { if (dest) bs._outputs.push(dest); return dest; },
                  start: function(t, offset, duration) {
                    bs._t0 = Math.max(0, Number(t) || 0);
                    bs._offset = Math.max(0, Number(offset) || 0);
                    bs._duration = duration !== undefined ? Number(duration) : -1;
                    scheduledSources.push(bs);
                  },
                  stop: function(t) { bs._stopT = Number(t) || 0; }
                };
                return bs;
              },
              startRendering: async function() {
                const out = bufData[0];

                function resolveNodeGain(node, timeSec) {
                  let factor = 1.0;
                  for (const outNode of (node._outputs || [])) {
                    if (outNode && outNode._type === "gain") {
                      factor *= outNode.gain.getValAt(timeSec);
                      factor *= resolveNodeGain(outNode, timeSec);
                    } else if (outNode && outNode._type === "filter") {
                      factor *= resolveNodeGain(outNode, timeSec);
                    }
                  }
                  return factor;
                }

                // Render oscillators with continuous phase and automated frequency/gain ramps
                for (const osc of scheduledOscs) {
                  const s0 = Math.max(0, Math.floor(osc._t0 * sr));
                  const s1 = Math.min(len, Math.floor((osc._t1 || osc._t0 + 0.25) * sr));
                  let phase = 0.0;
                  for (let i = s0; i < s1; i++) {
                    const curT = i / sr;
                    const freq = Math.max(10, osc.frequency.getValAt(curT));
                    phase += (2 * Math.PI * freq) / sr;
                    if (phase > 2 * Math.PI) phase -= 2 * Math.PI;

                    const ph = phase / (2 * Math.PI);
                    let w = Math.sin(phase);
                    if (osc.type === "triangle") {
                      w = 2 * Math.abs(2 * (ph - Math.floor(ph + 0.5))) - 1;
                    } else if (osc.type === "square") {
                      w = Math.sin(phase) >= 0 ? 1 : -1;
                    } else if (osc.type === "sawtooth") {
                      w = 2 * (ph - Math.floor(ph + 0.5));
                    }

                    const env = Math.min(1, (i - s0) / Math.max(1, sr * 0.008)) *
                                Math.min(1, (s1 - i) / Math.max(1, sr * 0.015));
                    const gainVal = resolveNodeGain(osc, curT);
                    out[i] = Math.max(-1, Math.min(1, out[i] + w * gainVal * 0.22 * env));
                  }
                }

                // Render buffer sources
                for (const bs of scheduledSources) {
                  if (!bs.buffer || !bs.buffer.getChannelData) continue;
                  const srcData = bs.buffer.getChannelData(0);
                  const s0 = Math.max(0, Math.floor(bs._t0 * sr));
                  const offSamples = Math.max(0, Math.floor(bs._offset * sr));
                  const maxSamples = bs._duration > 0
                    ? Math.min(srcData.length - offSamples, Math.floor(bs._duration * sr))
                    : (srcData.length - offSamples);
                  const s1 = Math.min(len, s0 + maxSamples);

                  for (let i = s0; i < s1; i++) {
                    const curT = i / sr;
                    const srcIdx = offSamples + (i - s0);
                    if (srcIdx >= 0 && srcIdx < srcData.length) {
                      const gainVal = resolveNodeGain(bs, curT);
                      out[i] = Math.max(-1, Math.min(1, out[i] + srcData[srcIdx] * gainVal));
                    }
                  }
                }

                return {
                  sampleRate: sr,
                  length: len,
                  duration: len / sr,
                  numberOfChannels: 1,
                  _channels: bufData,
                  getChannelData: () => bufData[0]
                };
              }
            };
          };
          globalThis.AudioContext = globalThis.OfflineAudioContext;
          globalThis.webkitAudioContext = globalThis.OfflineAudioContext;

          globalThis.console = {
            log: function(...a) { __host.log(a.map(String).join(" ")); },
            warn: function(...a) { __host.log("WARN: " + a.map(String).join(" ")); },
            error: function(...a) { __host.log("ERR: " + a.map(String).join(" ")); }
          };
        })();
    """.trimIndent()
}