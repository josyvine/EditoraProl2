package com.vineyard.editoraPRO.app.data.remote

/**
 * Exact prompt engineering contracts, RUNTIME_DOC_STRING, and 5-model Engine Selection Rules
 * uniting Instruction Set 1, Instruction Set 3, and the reference web architecture.
 */
object GeminiPrompts {

    val RUNTIME_DOC_STRING: String = listOf(
        "EXACT HOST RUNTIME API (passed as 'runtime' — raw primitives & GPU shader execution, ONLY use these exact methods):",
        "- runtime.getCanvas(key, w, h) -> returns reusable offscreen canvas of size w x h (ALWAYS perform masking/tinting/`source-in` compositing on an isolated offscreen canvas — NEVER run `source-in`/`destination-in` directly on main `ctx` after drawing background!).",
        "- await runtime.seekClip(mediaEl, timeSec) -> seeks video (safe instant no-op for images).",
        "- await runtime.getFrame(mediaEl, timeSec) -> works for BOTH videos AND images! Returns readable snapshot.",
        "- runtime.drawCover(ctx, media, x, y, w, h, zoom=1) -> draws media covering rect (x,y,w,h) without stretching/squashing aspect ratio.",
        "- await runtime.runShader(key, outW, outH, fragmentGlsl, texturesMap={}, uniformsMap={}) -> async raw WebGL2/OpenGL ES 3.0 GPU fragment shader runner! Always `await` it. Supports GLSL ES 1.00 and `#version 300 es`, auto-provides `v_uv` (0..1) and `u_resolution` (vec2(outW, outH)), binds textures and uniforms, returns canvas ready for `ctx.drawImage()`.",
        "- await runtime.detectBoxes(mediaEl, timeSec=0) -> returns [{ label: string, score: number, box: [x,y,w,h] }] (0..1 normalized) for 80 object classes.",
        "- await runtime.getMediaPipeMask(mediaEl, w, h) -> returns raw { width, height, data: Float32Array } (0.0..1.0) of ALL humans (front, back, side & crowds).",
        "- await runtime.segmentMediaPipe(mediaEl, w, h, featherOrOpts=0.45) -> transparent cutout canvas of ALL humans.",
        "- await runtime.getSamMask(mediaEl, w, h, promptOpts={}) -> promptOpts = { boxes: [[x,y,w,h], ...], points: [{x,y,label:1|0}, ...] } (omit or pass {} for native zero-click auto-detection + auto-center click on the dominant foreground subject; do NOT hardcode arbitrary points). Returns { width, height, data: Float32Array } (0.0..1.0).",
        "- await runtime.segmentSam(mediaEl, w, h, promptOpts={}, featherOrOpts=0.45) -> transparent HD cutout canvas of prompted or auto-detected subject (omit promptOpts or pass {} for native zero-click auto-click).",
        "- runtime.chromaKey(mediaEl, w, h, targetRGB=[0,200,0], tolerance=90, feather=35) -> chroma-key cutout canvas.",
        "- runtime.extractAudio(clip) -> synchronous (do NOT chain .catch() on it); returns pre-decoded AudioBuffer of clip (or null).",
        "- runtime.setAudioBuffer(audioBuffer) -> registers active audio track.",
        "- STATE & CANVAS CONTRACT: Draw directly onto passed 'ctx' (do NOT 'return canvas;'). Store persistent state in top-level variables. Compute all sizes and coordinates proportionally from (width, height). Time 't' is in seconds (0..duration); never multiply 't * duration'."
    ).joinToString("\n")

    val ENGINE_SELECTION_RULES: String = listOf(
        "SEGMENTATION ENGINE SELECTION RULES (ZERO-CLICK AUTO-SELECTION ACROSS ALL MODES):",
        "1. Choose MediaPipe (`runtime.segmentMediaPipe` / `runtime.getMediaPipeMask`) when:",
        "   - The cutout target is human (front view, back view, profile, dancers, crowds).",
        "   - Fast real-time multi-person segmentation or depth-behind-people is required.",
        "   - No external non-human accessories need to remain attached to the subject.",
        "2. Choose EdgeSAM (`runtime.segmentSam` / `runtime.getSamMask` with `{}` zero-click auto-prompt, or paired with `runtime.detectBoxes` for targeted multi-object scenes) when:",
        "   - The cutout target is NON-HUMAN (cars, motorcycles, pianos, guitars, pets, shoes, anime characters, products).",
        "   - The scene contains multiple people, but ONLY ONE specific individual must be isolated.",
        "   - A person is holding a complex accessory (guitar, skateboard, handbag) that MediaPipe would clip.",
        "   - Edge refinement is required for fine curly hair, transparent clothing, or high-definition silhouette cutouts.",
        "3. Log your chosen engine (`maskEngine: mediapipe` or `maskEngine: edgesam`) clearly in `[LOG]` lines and execute the matching primitive on the very first generation pass!"
    ).joinToString("\n")
}