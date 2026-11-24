; =====================================================================
; SCP-173 Containment Breach  -  P90 inventory / viewmodel  (P90.bb)
; ---------------------------------------------------------------------
; CS-style weapon handling:
;   "1"       : draw / holster the P90 (animated from below the screen)
;   LMB       : fire - tracer, muzzle flash, sound, weapon-only recoil
;   R         : reload (magazine drops, weapon dips, magazine returns)
;   HUD       : ammo / reserve counter at the bottom right
; The player/camera does NOT recoil - only the weapon kicks backwards
; and its muzzle climbs slightly upward.
; =====================================================================

Const P90_MAG% = 50
Const P90_RELOAD_FRAMES# = 105.0

Global P90_Obj% = 0
Global P90_ShotSnd% = 0, P90_Reload1Snd% = 0, P90_Reload2Snd% = 0
Global P90_DrawSnd% = 0, P90_BoltSnd% = 0, P90_DrySnd% = 0
Global P90_Muzzle% = 0, P90_Light% = 0
Global P90_BulletTemplate% = 0, P90_ImpactTemplate% = 0
Global P90_FlashTex% = 0, P90_ScorchTex% = 0, P90_ShockTex% = 0
Global P90_ScorchTemplate% = 0, P90_ShockTemplate% = 0
Global P90_FlashFrame# = 0.0

; 0 = holstered, 1 = drawing, 2 = ready, 3 = holstering
Global P90_State% = 0
Global P90_Anim# = 0.0
Global P90_Ammo% = P90_MAG
Global P90_Reserve% = 200
Global P90_Reloading% = False
Global P90_ReloadT# = 0.0
Global P90_ReloadStep% = 0
Global P90_FireCD# = 0.0

; weapon-only recoil + sway
Global P90_RecoilPos# = 0.0, P90_RecoilVel# = 0.0
Global P90_RecoilYaw# = 0.0, P90_RecoilYawVel# = 0.0
Global P90_Bob# = 0.0, P90_SwayX# = 0.0, P90_SwayY# = 0.0
Global P90_LastYaw# = 0.0
Global P90_Flash% = 0

Type PBullet
	Field obj%
	Field vx#, vy#, vz#
	Field life#
End Type

Type PImpact
	Field obj%
	Field life#
	Field kind%        ; 0 = scorch decal, 1 = shockwave, 2 = spark
	Field size#
End Type

; =====================================================================
; Build
; =====================================================================
Function P90_Build()
	If Camera = 0 Then Return

	P90_Obj = LoadMesh("GFX\P90.b3d")
	If P90_Obj = 0 Then Return

	Local t% = LoadTexture("GFX\p90texture.jpg")
	If t <> 0 Then EntityTexture P90_Obj, t

	EntityParent P90_Obj, Camera
	PositionEntity P90_Obj, 0, 0, 0
	EntityFX P90_Obj, 1
	EntityOrder P90_Obj, -1
	EntityPickMode P90_Obj, 0
	HideEntity P90_Obj

	; muzzle flash: animated, additively blended texture (from the FPS
	; example) instead of a plain glowing cube
	P90_FlashTex = LoadTexture("GFX\fury.png")
	If P90_FlashTex <> 0 Then TextureBlend P90_FlashTex, 2
	P90_Muzzle = CreateSprite()
	If P90_FlashTex <> 0 Then EntityTexture P90_Muzzle, P90_FlashTex
	EntityFX P90_Muzzle, 1
	EntityBlend P90_Muzzle, 3
	EntityAlpha P90_Muzzle, 0.9
	EntityOrder P90_Muzzle, -1
	EntityPickMode P90_Muzzle, 0
	EntityParent P90_Muzzle, Camera
	PositionEntity P90_Muzzle, 0.16, -0.12, 1.05
	ScaleSprite P90_Muzzle, 0.26, 0.26
	HideEntity P90_Muzzle

	P90_Light = CreateLight(2)
	LightColor P90_Light, 0, 0, 0
	LightRange P90_Light, 7.0
	PositionEntity P90_Light, 0.16, -0.12, 1.05
	EntityParent P90_Light, Camera

	; tracer template - small thin cylinder (Blitz3D cylinders run along Y)
	P90_BulletTemplate = CreateCylinder(8)
	ScaleEntity P90_BulletTemplate, 0.008, 0.05, 0.008
	EntityColor P90_BulletTemplate, 255, 200, 110
	EntityFX P90_BulletTemplate, 1 + 8
	EntityPickMode P90_BulletTemplate, 0
	HideEntity P90_BulletTemplate

	; impact decals - scorch mark and expanding shockwave, both aligned to
	; the surface normal exactly like in the example FPS level
	P90_ScorchTex = LoadTexture("GFX\scorch.png", 2)
	If P90_ScorchTex <> 0 Then TextureBlend P90_ScorchTex, 2
	P90_ScorchTemplate = CreateSprite()
	If P90_ScorchTex <> 0 Then EntityTexture P90_ScorchTemplate, P90_ScorchTex
	EntityFX P90_ScorchTemplate, 1
	EntityAlpha P90_ScorchTemplate, 0.8
	SpriteViewMode P90_ScorchTemplate, 2
	EntityPickMode P90_ScorchTemplate, 0
	HideEntity P90_ScorchTemplate

	P90_ShockTex = LoadTexture("GFX\shockwave.png", 2)
	If P90_ShockTex <> 0 Then TextureBlend P90_ShockTex, 2
	P90_ShockTemplate = CreateSprite()
	If P90_ShockTex <> 0 Then EntityTexture P90_ShockTemplate, P90_ShockTex
	EntityFX P90_ShockTemplate, 1
	EntityBlend P90_ShockTemplate, 3
	EntityAlpha P90_ShockTemplate, 0.8
	SpriteViewMode P90_ShockTemplate, 2
	EntityPickMode P90_ShockTemplate, 0
	HideEntity P90_ShockTemplate

	; tiny bright spark at the point of impact
	P90_ImpactTemplate = CreateCube()
	ScaleEntity P90_ImpactTemplate, 0.06, 0.06, 0.06
	EntityColor P90_ImpactTemplate, 255, 190, 90
	EntityFX P90_ImpactTemplate, 1 + 8
	EntityPickMode P90_ImpactTemplate, 0
	HideEntity P90_ImpactTemplate

	; real Counter-Strike: Global Offensive P90 sounds (sourcesounds/csgo)
	P90_ShotSnd    = LoadSound("SFX\Weapon\p90-1.wav")
	P90_Reload1Snd = LoadSound("SFX\Weapon\p90_clipout.wav")
	P90_Reload2Snd = LoadSound("SFX\Weapon\p90_clipin.wav")
	P90_DrawSnd    = LoadSound("SFX\Weapon\p90_draw.wav")
	P90_BoltSnd    = LoadSound("SFX\Weapon\p90_boltforward.wav")
	P90_DrySnd     = LoadSound("SFX\Weapon\p90_cliphit.wav")
	P90_LastYaw = EntityYaw(Collider)
	P90_Reset()
End Function

; =====================================================================
; Inventory slot 1
; =====================================================================
Function P90_Toggle()
	If P90_State = 0 Or P90_State = 3 Then
		P90_State = 1
		P90_Anim = 0.0
		ShowEntity P90_Obj
		If P90_DrawSnd <> 0 Then PlaySound P90_DrawSnd
		HintMSG = "P90 - HOLD LMB: FIRE   R: RELOAD   1: HOLSTER"
		HintTimer = 150
	ElseIf P90_State = 2 Then
		P90_State = 3
		P90_Reloading = False
		P90_ReloadStep = 0
		HintMSG = "P90 HOLSTERED"
		HintTimer = 90
	End If
End Function

Function P90_StartReload()
	If P90_Reloading Then Return
	If P90_Ammo >= P90_MAG Or P90_Reserve <= 0 Then Return
	P90_Reloading = True
	P90_ReloadT = 0.0
	P90_ReloadStep = 0
	PlaySound P90_Reload1Snd
End Function

; =====================================================================
; Bullets / impact sparks
; =====================================================================
Function P90_SpawnBullet()
	If P90_BulletTemplate = 0 Then Return
	Local b.PBullet = New PBullet
	b\obj = CopyEntity(P90_BulletTemplate)
	ShowEntity b\obj

	Local yaw# = EntityYaw(Camera, True)
	Local pitch# = EntityPitch(Camera, True)
	Local fx# = -Sin(yaw) * Cos(pitch)
	Local fy# = -Sin(pitch)
	Local fz# = Cos(yaw) * Cos(pitch)

	; FPS-example aiming: find where the crosshair actually points, then
	; fire from the muzzle towards that point instead of blindly along +Z.
	Local cx# = EntityX(Camera, True), cy# = EntityY(Camera, True), cz# = EntityZ(Camera, True)
	Local tx# = cx + fx * 200.0, ty# = cy + fy * 200.0, tz# = cz + fz * 200.0
	If LinePick(cx, cy, cz, fx * 200.0, fy * 200.0, fz * 200.0) <> 0 Then
		tx = PickedX() : ty = PickedY() : tz = PickedZ()
	End If

	; muzzle sits slightly right and below the eye
	Local rx# = Cos(yaw), rz# = Sin(yaw)
	Local ox# = cx + fx * 0.55 + rx * 0.14
	Local oy# = cy + fy * 0.55 - 0.10
	Local oz# = cz + fz * 0.55 + rz * 0.14

	b\vx = tx - ox : b\vy = ty - oy : b\vz = tz - oz
	Local dl# = Sqr(b\vx * b\vx + b\vy * b\vy + b\vz * b\vz)
	If dl > 0.001 Then
		b\vx = b\vx / dl : b\vy = b\vy / dl : b\vz = b\vz / dl
	End If

	PositionEntity b\obj, ox, oy, oz, True
	AlignToVector b\obj, b\vx, b\vy, b\vz, 2
	b\life = 45
End Function

Function P90_SpawnImpact(x#, y#, z#, nx#, ny#, nz#)
	; normal-aligned scorch decal
	If P90_ScorchTemplate <> 0 Then
		Local s.PImpact = New PImpact
		s\obj = CopyEntity(P90_ScorchTemplate)
		PositionEntity s\obj, x, y, z, True
		AlignToVector s\obj, -nx, -ny, -nz, 0
		MoveEntity s\obj, 0, 0, -0.02
		ScaleSprite s\obj, 0.7 + Rnd(0.0, 0.6), 0.7 + Rnd(0.0, 0.6)
		RotateSprite s\obj, Rnd(0, 360)
		ShowEntity s\obj
		s\life = 90 : s\kind = 0 : s\size = 1.0
	End If

	; expanding shockwave, also aligned to the hit surface
	If P90_ShockTemplate <> 0 Then
		Local w.PImpact = New PImpact
		w\obj = CopyEntity(P90_ShockTemplate)
		PositionEntity w\obj, x, y, z, True
		AlignToVector w\obj, -nx, -ny, -nz, 0
		MoveEntity w\obj, 0, 0, -0.03
		ScaleSprite w\obj, 0.2, 0.2
		RotateSprite w\obj, Rnd(0, 360)
		ShowEntity w\obj
		w\life = 24 : w\kind = 1 : w\size = 0.2
	End If

	; bright spark
	If P90_ImpactTemplate <> 0 Then
		Local k.PImpact = New PImpact
		k\obj = CopyEntity(P90_ImpactTemplate)
		PositionEntity k\obj, x, y, z, True
		ShowEntity k\obj
		k\life = 12 : k\kind = 2 : k\size = 1.0
	End If
End Function

Function P90_UpdateProjectiles()
	Local b.PBullet
	For b = Each PBullet
		Local x# = EntityX(b\obj, True), y# = EntityY(b\obj, True), z# = EntityZ(b\obj, True)
		Local nx# = x + b\vx * 1.7 * FpsFactor
		Local ny# = y + b\vy * 1.7 * FpsFactor
		Local nz# = z + b\vz * 1.7 * FpsFactor
		Local hit% = LinePick(x, y, z, nx - x, ny - y, nz - z)
		If hit <> 0 Then
			Local hx# = PickedX(), hy# = PickedY(), hz# = PickedZ()
			Local pxn# = PickedNX(), pyn# = PickedNY(), pzn# = PickedNZ()
			If Abs(pxn) + Abs(pyn) + Abs(pzn) < 0.01 Then
				pxn = -b\vx : pyn = -b\vy : pzn = -b\vz
			End If
			P90_SpawnImpact hx, hy, hz, pxn, pyn, pzn
			FreeEntity b\obj
			Delete b
		Else
			PositionEntity b\obj, nx, ny, nz, True
			b\life = b\life - FpsFactor
			If b\life <= 0 Then
				FreeEntity b\obj
				Delete b
			End If
		End If
	Next

	Local im.PImpact
	For im = Each PImpact
		im\life = im\life - FpsFactor
		Select im\kind
			Case 0   ; scorch decal fades slowly
				EntityAlpha im\obj, Clamp(im\life / 90.0, 0.0, 0.8)
			Case 1   ; shockwave expands and fades
				im\size = im\size + 0.16 * FpsFactor
				ScaleSprite im\obj, im\size, im\size
				EntityAlpha im\obj, Clamp(im\life / 24.0, 0.0, 0.8)
			Default  ; spark shrinks
				Local s# = Max(0.01, im\life / 12.0) * 0.06
				ScaleEntity im\obj, s, s, s
		End Select
		If im\life <= 0 Then
			FreeEntity im\obj
			Delete im
		End If
	Next
End Function

; =====================================================================
; Per-frame weapon update
; =====================================================================
Function P90_Update()
	If GameState <> GS_PLAY Then Return
	If KeyHit(2) Then P90_Toggle()   ; "1" = inventory slot

	P90_FireCD = Max(0.0, P90_FireCD - FpsFactor)

	; spring-damped recoil - ONLY the weapon, never the camera
	Local damp# = Max(0.40, 1.0 - 0.16 * FpsFactor)
	P90_RecoilVel = (P90_RecoilVel - P90_RecoilPos * 0.22 * FpsFactor) * damp
	P90_RecoilPos = P90_RecoilPos + P90_RecoilVel * FpsFactor
	; yaw kick also springs back to zero - the gun must never stay tilted
	P90_RecoilYawVel = (P90_RecoilYawVel - P90_RecoilYaw * 0.22 * FpsFactor) * damp
	P90_RecoilYaw = P90_RecoilYaw + P90_RecoilYawVel * FpsFactor
	If Abs(P90_RecoilYaw) < 0.001 And Abs(P90_RecoilYawVel) < 0.001 Then
		P90_RecoilYaw = 0 : P90_RecoilYawVel = 0
	End If

	P90_UpdateProjectiles()

	If P90_State = 0 Then
		If P90_Muzzle <> 0 Then HideEntity P90_Muzzle
		If P90_Light <> 0 Then LightColor P90_Light, 0, 0, 0
		Return
	End If

	; draw / holster animation
	If P90_State = 1 Then
		P90_Anim = Min(1.0, P90_Anim + 0.030 * FpsFactor)
		If P90_Anim >= 1.0 Then P90_State = 2
	ElseIf P90_State = 3 Then
		P90_Anim = Max(0.0, P90_Anim - 0.038 * FpsFactor)
		If P90_Anim <= 0.0 Then
			P90_State = 0
			HideEntity P90_Obj
		End If
	End If
	Local e# = 1.0 - (1.0 - P90_Anim) * (1.0 - P90_Anim) * (1.0 - P90_Anim)

	; reload timing
	If P90_State = 2 Then
		If KeyHit(19) Then P90_StartReload()   ; R
		If P90_Ammo = 0 And P90_Reserve > 0 Then P90_StartReload()
	End If
	If P90_Reloading Then
		P90_ReloadT = P90_ReloadT + FpsFactor
		If P90_ReloadStep = 0 And P90_ReloadT >= P90_RELOAD_FRAMES * 0.55 Then
			P90_ReloadStep = 1
			PlaySound P90_Reload2Snd
		End If
		If P90_ReloadStep = 1 And P90_ReloadT >= P90_RELOAD_FRAMES * 0.88 Then
			P90_ReloadStep = 2
			If P90_BoltSnd <> 0 Then PlaySound P90_BoltSnd
		End If
		If P90_ReloadT >= P90_RELOAD_FRAMES Then
			P90_Reloading = False
			Local need% = P90_MAG - P90_Ammo
			Local take% = Min(need, P90_Reserve)
			P90_Ammo = P90_Ammo + take
			P90_Reserve = P90_Reserve - take
		End If
	End If

	; fire - only when ready, not reloading, with ammo
	If P90_State = 2 And (Not P90_Reloading) And MouseDown(1) And P90_FireCD <= 0 Then
		If P90_Ammo > 0 Then
			P90_Ammo = P90_Ammo - 1
			P90_FireCD = 4.5
			P90_RecoilVel = 0.26
			P90_RecoilYawVel = Rnd(-0.045, 0.045)
			P90_Flash = 3
			P90_FlashFrame = Rnd(0.0, 1.0)
			FX_Shake 0.12
			PlaySound P90_ShotSnd
			P90_SpawnBullet()
		Else
			If P90_DrySnd <> 0 Then PlaySound P90_DrySnd
			P90_FireCD = 12.0
		End If
	End If

	; directional sway from turning
	Local dyaw# = WrapAngle(EntityYaw(Collider) - P90_LastYaw)
	P90_LastYaw = EntityYaw(Collider)
	P90_SwayX = Curve(Clamp(-dyaw * 0.035, -0.06, 0.06), P90_SwayX, 6.0)
	P90_SwayY = Curve(Clamp(Abs(dyaw) * 0.012, 0.0, 0.04), P90_SwayY, 7.0)

	; walking / running bob
	Local bx# = P90_SwayX, by# = -P90_SwayY, bz# = 0.0
	Local pitch# = 0.0, roll# = 0.0
	If Abs(PlayerSpeed) > 0.002 Then
		Local amp# = Abs(PlayerSpeed) / 0.038
		P90_Bob = P90_Bob + (0.24 + amp * 0.10) * FpsFactor
		bx = bx + Sin(P90_Bob) * 0.020 * amp
		by = by + Abs(Cos(P90_Bob)) * 0.016 * amp
		roll = Sin(P90_Bob) * 2.2 * amp
	Else
		P90_Bob = Curve(0.0, P90_Bob, 8.0)
		bx = bx + Sin(GameTime * 1.3) * 0.003
		by = by + Sin(GameTime * 1.1) * 0.002
	End If

	; CS-style draw/holster: rises from below the screen, tilted down
	by = by - (1.0 - e) * 0.55
	pitch = pitch + (1.0 - e) * 22.0

	; reload animation: dip left/down, tilt, then return
	If P90_Reloading Then
		Local rp# = Clamp(P90_ReloadT / P90_RELOAD_FRAMES, 0.0, 1.0)
		Local s# = Sin(rp * 180.0)
		bx = bx - 0.09 * s
		by = by - 0.22 * s
		roll = roll - 24.0 * s
		pitch = pitch + 16.0 * s
	End If

	; recoil: straight backwards only - no upward climb
	bz = bz - P90_RecoilPos * 0.24
	roll = roll + P90_RecoilYaw * 25.0

	PositionEntity P90_Obj, bx, by, bz
	RotateEntity P90_Obj, pitch, 0, roll

	; muzzle flash - animated texture, as in the FPS example
	If P90_Flash > 0 Then
		P90_Flash = P90_Flash - 1
		If P90_Muzzle <> 0 Then
			ShowEntity P90_Muzzle
			If P90_FlashTex <> 0 Then
				P90_FlashFrame = P90_FlashFrame + 0.37
				PositionTexture P90_FlashTex, P90_FlashFrame Mod 1.0, P90_FlashFrame Mod 1.0
			End If
			RotateSprite P90_Muzzle, Rnd(0, 360)
			Local fs# = Rnd(0.22, 0.40)
			ScaleSprite P90_Muzzle, fs, fs
		End If
		If P90_Light <> 0 Then LightColor P90_Light, 255, 225, 150
	Else
		If P90_Muzzle <> 0 Then HideEntity P90_Muzzle
		If P90_Light <> 0 Then LightColor P90_Light, 0, 0, 0
	End If
End Function

; =====================================================================
; HUD - ammo counter (CS style, bottom right)
; =====================================================================
Function P90_DrawHUD()
	If P90_State = 0 Then Return
	SetBuffer BackBuffer()

	Local x% = GraphW - 48 * MenuScale
	Local y% = GraphH - 46 * MenuScale

	SetFont FntBig
	If P90_Ammo = 0 Then Color 230, 70, 70 Else Color 245, 245, 245
	Text x, y, P90_Ammo, True, True

	SetFont FntTitle
	Color 150, 150, 155
	Text x + 62 * MenuScale, y + 20 * MenuScale, "/ " + P90_Reserve, True, True

	SetFont FntSmall
	Color 190, 190, 195
	Text x - 10 * MenuScale, y - 48 * MenuScale, "P90", True

	If P90_Reloading Then
		Color 255, 200, 80
		Text x, y - 75 * MenuScale, "RELOADING", True
	End If
	SetFont FntTitle
End Function

Function P90_Reset()
	P90_State = 0 : P90_Anim = 0.0
	P90_Ammo = P90_MAG : P90_Reserve = 200
	P90_Reloading = False : P90_ReloadT = 0.0 : P90_ReloadStep = 0
	P90_FireCD = 0.0 : P90_Flash = 0 : P90_FlashFrame = 0.0
	P90_RecoilPos = 0 : P90_RecoilVel = 0
	P90_RecoilYaw = 0 : P90_RecoilYawVel = 0
	P90_Bob = 0 : P90_SwayX = 0 : P90_SwayY = 0
	If P90_Obj <> 0 Then HideEntity P90_Obj
	If P90_Muzzle <> 0 Then HideEntity P90_Muzzle
	If P90_Light <> 0 Then LightColor P90_Light, 0, 0, 0
	Local b.PBullet
	For b = Each PBullet : FreeEntity b\obj : Delete b : Next
	Local im.PImpact
	For im = Each PImpact : FreeEntity im\obj : Delete im : Next
End Function
