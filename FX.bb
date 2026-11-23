; =====================================================================
; SCP-173 Containment Breach  -  VISUAL EFFECTS  (FX.bb)
; ---------------------------------------------------------------------
; Always-on effects (no keys, no options needed):
;  * camera : trauma shake, smoothing, strafe roll, sprint FOV, head bob
;             and breathing drift
;  * lights : flickering fluorescent tubes, cold chamber fill light and
;             red emergency strobes that activate on the breach, plus a
;             camera-mounted flashlight (spotlight)
;  * screen : short chromatic edge glitches only. No grain, no scanlines,
;             no dither, no particles.
; =====================================================================

; ----- camera state ---------------------------------------------------
Global FX_Trauma# = 0.0
Global FX_Roll#   = 0.0
Global FX_ShakeX# = 0.0, FX_ShakeY# = 0.0, FX_ShakeZ# = 0.0
Global FX_ShakePitch# = 0.0, FX_ShakeYaw# = 0.0

; ----- screen state ---------------------------------------------------
Global FX_Danger# = 0.0
Global FX_Death#  = 0.0
Global FX_Alarm#  = 0.0
Global FX_Storm#  = 0.0

; ----- objects --------------------------------------------------------
Global FX_Lamp% = 0

; ----- dynamic lights -------------------------------------------------
Type FXLight
	Field obj%
	Field cr%, cg%, cb%
	Field range#
	Field phase#
	Field freq#
	Field mode%       ; 0 = soft pulse, 1 = fluorescent, 2 = emergency strobe
	Field glitch#
	Field state%
	Field timer#
End Type

; =====================================================================
; World effects setup (called from BuildWorld, after the camera exists)
; =====================================================================
Function FX_AddLight(x#, y#, z#, r%, g%, b%, range#, mode% = 0, glitch# = 0.02)
	Local l.FXLight = New FXLight
	l\obj = AddRoomLight(x, y, z, r, g, b, range)
	l\cr = r : l\cg = g : l\cb = b : l\range = range
	l\phase = Rnd(0.0, 1000.0)
	l\freq = Rnd(1.2, 3.6)
	l\mode = mode
	l\glitch = glitch
	l\state = 1
	l\timer = Rnd(5.0, 70.0)
	Return l\obj
End Function

Function FX_Build()
	If Camera = 0 Then Return

	; camera-mounted flashlight
	FX_Lamp = CreateLight(3)
	LightColor FX_Lamp, 205, 212, 228
	LightRange FX_Lamp, 13.0
	LightConeAngles FX_Lamp, 15, 45
	EntityParent FX_Lamp, Camera

	; unstable tubes in the cell block and corridor
	FX_AddLight -20.5, 2.7, -6.0, 175, 185, 205, 11, 1, 0.035
	FX_AddLight -20.5, 2.7,  8.0, 175, 185, 205, 11, 1, 0.025
	FX_AddLight  -9.0, 2.7, -3.0, 170, 180, 200, 10, 1, 0.020
	FX_AddLight  -9.0, 2.7,  5.0, 170, 180, 200, 10, 1, 0.040

	; cold chamber fill + red emergency strobes (dark until the breach)
	FX_AddLight   4.5, 3.0, 3.0, 120, 150, 190, 12, 0
	FX_AddLight   2.0, 2.6, 1.0, 255, 40,  35, 13, 2
	FX_AddLight   6.5, 2.6, 5.5, 255, 40,  35, 13, 2
End Function

; =====================================================================
; Per-frame update
; =====================================================================
Function FX_UpdateLights()
	Local l.FXLight
	For l = Each FXLight
		Local f# = 1.0
		Select l\mode
			Case 0
				f = 0.92 + 0.08 * Sin(GameTime * l\freq + l\phase)
			Case 1
				l\timer = l\timer - FpsFactor
				If l\timer <= 0 Then
					l\timer = Rnd(10.0, 110.0)
					If Rnd(0.0, 1.0) < l\glitch Then l\state = 0 Else l\state = 1
				End If
				If l\state = 0 Then
					f = Rnd(0.02, 0.35)
					If Rnd(0.0, 1.0) < 0.18 Then l\state = 1
				Else
					f = 0.95 + 0.05 * Sin(GameTime * 9.0 + l\phase)
				End If
			Case 2
				f = (0.15 + 0.85 * Abs(Sin(GameTime * 7.0 + l\phase))) * FX_Alarm
		End Select
		If FX_Storm > 0.0 Then f = f * (0.25 + 0.75 * Rnd(0.0, 1.0))
		LightColor l\obj, Int(l\cr * f), Int(l\cg * f), Int(l\cb * f)
		LightRange l\obj, l\range * (0.8 + 0.2 * f)
	Next
End Function

Function FX_Update()
	FX_Trauma = Max(0.0, FX_Trauma - 0.020 * FpsFactor)
	FX_Storm  = Max(0.0, FX_Storm  - 0.010 * FpsFactor)
	FX_Alarm  = Min(1.0, FX_Alarm + 0.020 * FpsFactor)

	; lean into strafing
	Local targetRoll# = 0.0
	If KeyDown(30) Then targetRoll = 2.2
	If KeyDown(32) Then targetRoll = -2.2
	FX_Roll = Curve(targetRoll, FX_Roll, 9.0)

	; smoothed shake offsets
	Local t# = FX_Trauma * FX_Trauma
	Local mag# = t * 0.055
	FX_ShakeX     = Curve(Rnd(-mag, mag), FX_ShakeX, 2.2)
	FX_ShakeY     = Curve(Rnd(-mag, mag), FX_ShakeY, 2.2)
	FX_ShakeZ     = Curve(Rnd(-mag, mag), FX_ShakeZ, 2.2)
	FX_ShakePitch = Curve(Rnd(-t * 1.2, t * 1.2), FX_ShakePitch, 2.5)
	FX_ShakeYaw   = Curve(Rnd(-t * 1.2, t * 1.2), FX_ShakeYaw, 2.5)

	FX_UpdateLights()
End Function

; applied after the player/camera logic, right before rendering
Function FX_ApplyCamera()
	If Camera = 0 Or GameState <> GS_PLAY Then Return

	Local moving% = (Abs(PlayerSpeed) > 0.002)
	If moving Then
		Local b# = Abs(PlayerSpeed) / 0.038
		PositionEntity Camera, EntityX(Camera) + FX_ShakeX, EntityY(Camera) + Sin(StepBob * 2.0) * 0.010 * b + FX_ShakeY, EntityZ(Camera) + FX_ShakeZ
		TranslateEntity Camera, Cos(StepBob) * 0.006 * b, 0, 0
	Else
		PositionEntity Camera, EntityX(Camera) + FX_ShakeX, EntityY(Camera) + Sin(GameTime * 1.6) * 0.003 + FX_ShakeY, EntityZ(Camera) + FX_ShakeZ
	End If

	RotateEntity Camera, EntityPitch(Camera) + FX_ShakePitch, EntityYaw(Camera) + FX_ShakeYaw, FX_Roll

	; sprint widens the FOV, proximity to 173 tightens it
	Local z# = 1.0
	If KeyDown(42) And moving Then z = 0.95
	z = z - FX_Danger * 0.03
	CameraZoom Camera, Clamp(z + CurrZoom / 400.0, 0.8, 1.5)
End Function

; =====================================================================
; Event hooks
; =====================================================================
Function FX_Shake(amount#)
	FX_Trauma = Min(1.0, FX_Trauma + amount)
End Function

Function FX_OnBreach()
	FX_Storm = 1.0
	FX_Alarm = 0.001
	FX_Shake 0.55
End Function

Function FX_OnKill()
	FX_Shake 1.0
	FX_Storm = 1.0
	FX_Death = 1.0
End Function

Function FX_OnBlink()
	FX_Shake 0.10
End Function

Function FX_Reset()
	FX_Trauma = 0 : FX_Roll = 0
	FX_ShakeX = 0 : FX_ShakeY = 0 : FX_ShakeZ = 0
	FX_ShakePitch = 0 : FX_ShakeYaw = 0
	FX_Danger = 0 : FX_Death = 0
	FX_Alarm = 0 : FX_Storm = 0
	If Camera <> 0 Then CameraZoom Camera, 1.0
End Function

; =====================================================================
; 2D screen effects (no particles)
; =====================================================================
Function FX_DrawGlitch()
	If FX_Trauma < 0.35 Then Return
	Color 200, 30, 30 : Rect Rnd(-8, 8), 0, GraphW, 2, True
	Color 30, 200, 200 : Rect Rnd(-8, 8), GraphH - 2, GraphW, 2, True
End Function

Function FX_DrawScreen()
	SetBuffer BackBuffer()
	If GameState = GS_MENU Or GameState = GS_PAUSE Then Return
	FX_DrawGlitch()
	FX_Death = Max(0.0, FX_Death - 0.004 * FpsFactor)
End Function
