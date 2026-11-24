; =====================================================================
; SCP-173 Containment Breach - REFLECTIONS (Reflections.bb)
; ---------------------------------------------------------------------
; High-quality Blitz3D / DirectX 7 reflection system.
;
; Main fixes compared with the previous version:
;   1) No single fixed Y=0.012 floor quad. The reflective meshes are
;      rebuilt from the actual upward-facing triangles in RoomOpaque.
;      Different floor heights and room elevations are therefore covered.
;   2) The cubemap probe is placed at the player's current floor height.
;      Its height is found from the actual RoomOpaque triangles, not by
;      mirroring the camera below one global plane.
;   3) The main camera is hidden while a cubemap face is rendered. This
;      prevents the normal view from contaminating the cubemap capture.
;   4) One cubemap face is refreshed per update instead of rendering all
;      six faces in one frame. This spreads the capture cost across frames.
;   5) The reflection pass is floor-only. Walls and ceilings are not
;      accidentally given the same wet-floor material.
;
; Integration:
;   - Include this file after the game's globals are declared.
;   - Call RF_Init() from BuildWorld() after RoomObj, RoomOpaque,
;     Camera and N173_Obj have been created.
;   - Call RF_UpdateCubemaps(Camera) after UpdateWorld() and before the
;     normal RenderWorld call.
;   - Call RF_Reset() immediately after ClearWorld() and before rebuilding.
;
; Note:
;   This is a real-time cubemap approximation, not shader-based SSR or
;   physically-based ray tracing. Blitz3D's fixed-function pipeline does
;   not provide those modern effects.
; =====================================================================

; ----- state ---------------------------------------------------------
Global RF_Ready%          = False
Global RF_CubeOK%         = False
Global RF_CubeCam%        = 0
Global RF_Floor%          = 0
Global RF_Wall%           = 0       ; reserved for compatibility; unused
Global RF_FloorTex%       = 0
Global RF_SpecFloor%      = 0
Global RF_Spec173%        = 0
Global RF_Frame%          = 0
Global RF_Face%           = 0

; ----- quality / tuning ---------------------------------------------
Global RF_CubeSize%       = 256     ; 256 = high quality; 512 is much slower
Global RF_Interval%       = 1       ; one cubemap face per game update
Global RF_ReflectR%       = 238
Global RF_ReflectG%       = 246
Global RF_ReflectB%       = 255
Global RF_ReflectAlpha#   = 0.30
Global RF_SpecAlpha#      = 0.17
Global RF_Spec173Alpha#   = 0.38
Global RF_FloorNormalMin# = 0.78    ; only near-horizontal upward faces
Global RF_ProbeLift#      = 1.5     ; multiplied by RoomScale
Global RF_FallbackEye#    = 160.0   ; multiplied by RoomScale
Global RF_FogNear#        = 24.0
Global RF_FogFar#         = 100.0
Global RF_ProbeX#         = 0.0
Global RF_ProbeY#         = 0.0
Global RF_ProbeZ#         = 0.0

; =====================================================================
; Build reflective geometry from actual map floor triangles
; =====================================================================

; Creates a mesh containing only upward-facing, nearly-horizontal
; triangles from RoomOpaque. Every source vertex is transformed into
; RoomObj-local space before being copied, so the overlay follows the
; room transform and supports floors at different elevations.
;
; offsetWorld# is the desired world-space separation from the original
; floor. It is converted to RoomObj-local units using RoomScale.
Function RF_BuildFloorMesh(offsetWorld#)
	If RoomOpaque = 0 Or RoomObj = 0 Then Return 0

	Local m% = CreateMesh()
	EntityParent m, RoomObj, 0
	PositionEntity m, 0, 0, 0
	RotateEntity m, 0, 0, 0
	ScaleEntity m, 1, 1, 1

	Local offset# = offsetWorld
	If RoomScale <> 0 Then offset = offsetWorld / RoomScale

	Local total% = 0
	Local s%, src%, dst%, tr%
	Local v0%, v1%, v2%
	Local x0#, y0#, z0#
	Local x1#, y1#, z1#
	Local x2#, y2#, z2#
	Local ex1#, ey1#, ez1#
	Local ex2#, ey2#, ez2#
	Local nx#, ny#, nz#, nlen#
	Local a%, b%, c%

	For s = 1 To CountSurfaces(RoomOpaque)
		src = GetSurface(RoomOpaque, s)
		dst = CreateSurface(m)

		For tr = 0 To CountTriangles(src) - 1
			v0 = TriangleVertex(src, tr, 0)
			v1 = TriangleVertex(src, tr, 1)
			v2 = TriangleVertex(src, tr, 2)

			; Source mesh -> RoomObj local coordinates.
			TFormPoint VertexX(src, v0), VertexY(src, v0), VertexZ(src, v0), RoomOpaque, RoomObj
			x0 = TFormedX() : y0 = TFormedY() : z0 = TFormedZ()
			TFormPoint VertexX(src, v1), VertexY(src, v1), VertexZ(src, v1), RoomOpaque, RoomObj
			x1 = TFormedX() : y1 = TFormedY() : z1 = TFormedZ()
			TFormPoint VertexX(src, v2), VertexY(src, v2), VertexZ(src, v2), RoomOpaque, RoomObj
			x2 = TFormedX() : y2 = TFormedY() : z2 = TFormedZ()

			ex1 = x1 - x0 : ey1 = y1 - y0 : ez1 = z1 - z0
			ex2 = x2 - x0 : ey2 = y2 - y0 : ez2 = z2 - z0

			; Geometric face normal. Positive Y means the visible face
			; points upward, which is what we want for floor reflections.
			nx = ey1 * ez2 - ez1 * ey2
			ny = ez1 * ex2 - ex1 * ez2
			nz = ex1 * ey2 - ey1 * ex2
			nlen = Sqr(nx * nx + ny * ny + nz * nz)

			If nlen > 0.00001 Then
				nx = nx / nlen : ny = ny / nlen : nz = nz / nlen

				If ny >= RF_FloorNormalMin Then
					; Nudge along the normal to prevent z-fighting.
					x0 = x0 + nx * offset : y0 = y0 + ny * offset : z0 = z0 + nz * offset
				x1 = x1 + nx * offset : y1 = y1 + ny * offset : z1 = z1 + nz * offset
				x2 = x2 + nx * offset : y2 = y2 + ny * offset : z2 = z2 + nz * offset

				a = AddVertex(dst, x0, y0, z0, VertexU(src, v0), VertexV(src, v0))
				b = AddVertex(dst, x1, y1, z1, VertexU(src, v1), VertexV(src, v1))
				c = AddVertex(dst, x2, y2, z2, VertexU(src, v2), VertexV(src, v2))

				VertexNormal dst, a, nx, ny, nz
				VertexNormal dst, b, nx, ny, nz
				VertexNormal dst, c, nx, ny, nz
				AddTriangle dst, a, b, c
				total = total + 1
				EndIf
			EndIf
		Next
	Next

	If total = 0 Then
		FreeEntity m
		Return 0
	EndIf

	EntityFX m, 1 + 16       ; fullbright overlay, double-sided safety
	Return m
End Function

; =====================================================================
; Find the floor under the player without requiring EntityPickMode.
; The search tests RoomOpaque's upward-facing triangles in RoomObj-local
; X/Z and selects the highest floor below the camera.
; =====================================================================
Function RF_FindFloorY#(maincamera%)
	If RoomOpaque = 0 Or RoomObj = 0 Then
		If RoomScale <> 0 Then Return EntityY(maincamera, True) - RF_FallbackEye * RoomScale
		Return EntityY(maincamera, True) - 1.6
	EndIf

	Local wx# = EntityX(maincamera, True)
	Local wy# = EntityY(maincamera, True)
	Local wz# = EntityZ(maincamera, True)

	TFormPoint wx, wy, wz, 0, RoomObj
	Local px# = TFormedX()
	Local py# = TFormedY()
	Local pz# = TFormedZ()

	Local bestY# = -1000000.0
	Local s%, src%, tr%
	Local v0%, v1%, v2%
	Local x0#, y0#, z0#
	Local x1#, y1#, z1#
	Local x2#, y2#, z2#
	Local ex1#, ey1#, ez1#
	Local ex2#, ey2#, ez2#
	Local nx#, ny#, nz#, nlen#
	Local den#, w0#, w1#, w2#, candidateY#

	For s = 1 To CountSurfaces(RoomOpaque)
		src = GetSurface(RoomOpaque, s)
		For tr = 0 To CountTriangles(src) - 1
			v0 = TriangleVertex(src, tr, 0)
			v1 = TriangleVertex(src, tr, 1)
			v2 = TriangleVertex(src, tr, 2)

			TFormPoint VertexX(src, v0), VertexY(src, v0), VertexZ(src, v0), RoomOpaque, RoomObj
			x0 = TFormedX() : y0 = TFormedY() : z0 = TFormedZ()
			TFormPoint VertexX(src, v1), VertexY(src, v1), VertexZ(src, v1), RoomOpaque, RoomObj
			x1 = TFormedX() : y1 = TFormedY() : z1 = TFormedZ()
			TFormPoint VertexX(src, v2), VertexY(src, v2), VertexZ(src, v2), RoomOpaque, RoomObj
			x2 = TFormedX() : y2 = TFormedY() : z2 = TFormedZ()

			ex1 = x1 - x0 : ey1 = y1 - y0 : ez1 = z1 - z0
			ex2 = x2 - x0 : ey2 = y2 - y0 : ez2 = z2 - z0
			nx = ey1 * ez2 - ez1 * ey2
			ny = ez1 * ex2 - ex1 * ez2
			nz = ex1 * ey2 - ey1 * ex2
			nlen = Sqr(nx * nx + ny * ny + nz * nz)

			If nlen > 0.00001 Then
				If ny / nlen >= RF_FloorNormalMin Then
					; Barycentric test in X/Z; accepted points lie inside
					; this floor triangle.
					den = (z1 - z2) * (x0 - x2) + (x2 - x1) * (z0 - z2)
					If Abs(den) > 0.000001 Then
						w0 = ((z1 - z2) * (px - x2) + (x2 - x1) * (pz - z2)) / den
						w1 = ((z2 - z0) * (px - x2) + (x0 - x2) * (pz - z2)) / den
						w2 = 1.0 - w0 - w1

						If w0 >= -0.002 And w1 >= -0.002 And w2 >= -0.002 Then
							candidateY = w0 * y0 + w1 * y1 + w2 * y2
							If candidateY < py And candidateY > bestY Then bestY = candidateY
						EndIf
					EndIf
				EndIf
			EndIf
		Next
	Next

	If bestY > -999999.0 Then
		TFormPoint px, bestY, pz, RoomObj, 0
		Return TFormedY()
	EndIf

	; Fallback for rooms whose floor triangles are not stored in RoomOpaque.
	If RoomScale <> 0 Then Return wy - RF_FallbackEye * RoomScale
	Return wy - 1.6
End Function

; =====================================================================
; Specular material helpers
; =====================================================================

Function RF_MakeSpecTex()
	Local t% = CreateTexture(256, 256, 1 + 8)
	SetBuffer TextureBuffer(t)
	ClsColor 92, 92, 92
	Cls

	Local i%
	Color 48, 48, 48
	For i = 1 To 90
		Oval Rnd(0, 256), Rnd(0, 256), Rnd(5, 34), Rnd(5, 34), True
	Next

	Color 128, 128, 128
	For i = 1 To 32
		Oval Rnd(0, 256), Rnd(0, 256), Rnd(4, 22), Rnd(4, 22), True
	Next

	Color 170, 170, 170
	For i = 1 To 8
		Oval Rnd(0, 256), Rnd(0, 256), Rnd(3, 12), Rnd(3, 12), True
	Next

	ClsColor 0, 0, 0
	SetBuffer BackBuffer()
	Return t
End Function

Function RF_MakeSpecBrush(tex%)
	Local b% = CreateBrush()
	BrushTexture b, tex
	BrushBlend b, 3
	BrushColor b, 0, 0, 0
	BrushShininess b, 0.60
	Return b
End Function

; Paint every mesh in a copied model hierarchy, not just its root.
Function RF_PaintTree(e%, brush%, alpha#)
	If e = 0 Then Return
	Local n% = CountChildren(e)
	Local i%
	PaintEntity e, brush
	EntityFX e, 0
	EntityAlpha e, alpha
	For i = 1 To n
		RF_PaintTree GetChild(e, i), brush, alpha
	Next
End Function

; =====================================================================
; Build - call from BuildWorld() after the room, camera and SCP-173 exist
; =====================================================================
Function RF_Init()
	RF_Ready = False
	RF_CubeOK = False
	RF_CubeCam = 0
	RF_Floor = 0
	RF_Wall = 0
	RF_FloorTex = 0
	RF_SpecFloor = 0
	RF_Spec173 = 0
	RF_Frame = 0
	RF_Face = 0

	If Camera = 0 Then Return

	If GfxDriverCaps3D() >= 110 Then
		RF_CubeOK = True
	Else
		RF_CubeOK = False
	EndIf

	If RF_CubeOK Then
		RF_CubeCam = CreateCamera()
		HideEntity RF_CubeCam
		CameraRange RF_CubeCam, 0.1, 120.0
		CameraZoom RF_CubeCam, 1.0

		; Colour + mipmaps + clamp U/V + cubic environment map + VRAM.
		RF_FloorTex = CreateTexture(RF_CubeSize, RF_CubeSize, 1 + 8 + 16 + 32 + 128 + 256)
		TextureBlend RF_FloorTex, 2
		SetCubeMode RF_FloorTex, 1

		RF_Floor = RF_BuildFloorMesh(0.012)
		If RF_Floor <> 0 Then
			EntityTexture RF_Floor, RF_FloorTex, 0, 0
			EntityColor RF_Floor, RF_ReflectR, RF_ReflectG, RF_ReflectB
			EntityFX RF_Floor, 1 + 16
			EntityBlend RF_Floor, 3
			EntityAlpha RF_Floor, RF_ReflectAlpha
		EndIf
	EndIf

	; DX7-style soft specular breakup on every actual floor triangle.
	RF_SpecFloor = RF_BuildFloorMesh(0.020)
	If RF_SpecFloor <> 0 Then
		Local specTex% = RF_MakeSpecTex()
		Local sb% = RF_MakeSpecBrush(specTex)
		PaintEntity RF_SpecFloor, sb
		EntityFX RF_SpecFloor, 0
		EntityBlend RF_SpecFloor, 3
		EntityAlpha RF_SpecFloor, RF_SpecAlpha
		FreeBrush sb
	EndIf

	; Separate specular pass for SCP-173.
	If N173_Obj <> 0 Then
		Local st% = LoadTexture("GFX\npcs\173_Spec.jpg", 1 + 8)
		If st <> 0 Then
			Local b173% = RF_MakeSpecBrush(st)
			RF_Spec173 = CopyEntity(N173_Obj)
			EntityParent RF_Spec173, N173_Obj, 0
			PositionEntity RF_Spec173, 0, 0, 0
			RotateEntity RF_Spec173, 0, 0, 0
			ScaleEntity RF_Spec173, 1.002, 1.002, 1.002
			RF_PaintTree RF_Spec173, b173, RF_Spec173Alpha
			FreeBrush b173
		EndIf
	EndIf

	RF_Ready = True
End Function

; =====================================================================
; One cubemap face per update. Call after UpdateWorld() and before the
; game's regular RenderWorld(). The probe is held still for all six faces.
; =====================================================================
Function RF_UpdateCubemaps(maincamera%)
	If RF_Ready = False Or RF_CubeOK = False Then Return
	If RF_CubeCam = 0 Or RF_FloorTex = 0 Or maincamera = 0 Then Return
	If GameState <> GS_PLAY And GameState <> GS_DEAD Then Return

	RF_Frame = RF_Frame + 1
	If RF_Frame Mod RF_Interval <> 0 Then Return

	; Lock the probe position for a complete six-face update.
	If RF_Face = 0 Then
		RF_ProbeX = EntityX(maincamera, True)
		RF_ProbeZ = EntityZ(maincamera, True)
		RF_ProbeY = RF_FindFloorY(maincamera)
		If RoomScale <> 0 Then
			RF_ProbeY = RF_ProbeY + RF_ProbeLift * RoomScale
		Else
			RF_ProbeY = RF_ProbeY + 0.02
		EndIf
	EndIf

	; Hide all reflection layers so they cannot reflect themselves.
	If RF_Floor <> 0 Then HideEntity RF_Floor
	If RF_SpecFloor <> 0 Then HideEntity RF_SpecFloor
	If RF_Spec173 <> 0 Then HideEntity RF_Spec173

	; Hide first-person weapon / muzzle flash from the environment probe.
	Local p90shown% = (P90_Obj <> 0 And (P90_State = 1 Or P90_State = 2))
	Local flashshown% = (P90_Muzzle <> 0 And P90_Flash <> 0)
	If p90shown Then HideEntity P90_Obj
	If flashshown Then HideEntity P90_Muzzle

	; IMPORTANT: render only from the cubemap camera.
	HideEntity maincamera
	SetBuffer BackBuffer()

	Local cam% = RF_CubeCam
	Local tex% = RF_FloorTex
	Local sz% = TextureWidth(tex)

	PositionEntity cam, RF_ProbeX, RF_ProbeY, RF_ProbeZ
	CameraRange cam, 0.1, 120.0
	CameraViewport cam, 0, 0, sz, sz
	CameraClsMode cam, True, True
	CameraClsColor cam, 13, 16, 21
	CameraFogMode cam, 1
	CameraFogRange cam, RF_FogNear, RF_FogFar
	CameraFogColor cam, 13, 16, 21
	ShowEntity cam

	SetCubeFace tex, RF_Face
	Select RF_Face
		Case 0
			RotateEntity cam, 0, 90, 0
		Case 1
			RotateEntity cam, 0, 0, 0
		Case 2
			RotateEntity cam, 0, -90, 0
		Case 3
			RotateEntity cam, 0, 180, 0
		Case 4
			RotateEntity cam, -90, 0, 0
		Case 5
			RotateEntity cam, 90, 0, 0
	End Select

	RenderWorld
	CopyRect 0, 0, sz, sz, 0, 0, BackBuffer(), TextureBuffer(tex)

	HideEntity cam
	ShowEntity maincamera

	If RF_Floor <> 0 Then ShowEntity RF_Floor
	If RF_SpecFloor <> 0 Then ShowEntity RF_SpecFloor
	If RF_Spec173 <> 0 Then ShowEntity RF_Spec173
	If P90_Obj <> 0 And p90shown Then ShowEntity P90_Obj
	If P90_Muzzle <> 0 And flashshown Then ShowEntity P90_Muzzle

	SetBuffer BackBuffer()

	RF_Face = RF_Face + 1
	If RF_Face > 5 Then RF_Face = 0
End Function

; =====================================================================
; ClearWorld() frees entities/textures. Reset handles before rebuilding.
; Also call this before a fullscreen / graphics-mode recreation.
; =====================================================================
Function RF_Reset()
	RF_Ready = False
	RF_CubeOK = False
	RF_CubeCam = 0
	RF_Floor = 0
	RF_Wall = 0
	RF_FloorTex = 0
	RF_SpecFloor = 0
	RF_Spec173 = 0
	RF_Frame = 0
	RF_Face = 0
	RF_ProbeX = 0
	RF_ProbeY = 0
	RF_ProbeZ = 0
End Function
