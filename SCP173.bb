; =====================================================================
; SCP-173 Containment Breach  -  GAMEPLAY
; ---------------------------------------------------------------------
; The observation/blink rule, the chase AI, the kill and the player are
; ported from the original game's NPCs.bb / Main.bb. The scripted intro
; is replaced by a short "walk out of the cell, the chamber door opens"
; sequence, because this project only contains the first spawn map.
; =====================================================================

Const GS_MENU% = 0, GS_PLAY% = 1, GS_DEAD% = 2, GS_PAUSE% = 3
Const HIT_173% = 7   ; sensor only - the player can touch 173 but never push it
Global GameState% = GS_MENU

; ----- scale / world -------------------------------------------------
Global RoomScale# = 8.0 / 2048.0   ; same as original

; ----- player --------------------------------------------------------
Global PlayerSpeed# = 0.0
Global UserPitch# = 0.0
Global DropSpeed# = 0.0
Global StepBob# = 0.0
Global SpawnX#, SpawnY#, SpawnZ#

; ----- blink system (Main.bb 284-285, 3048-3076) ---------------------
Global BlinkTimer# = -10.0
Global BLINKFREQ# = 500.0
Global BlinkMeterIMG%
Global BlurVolume# = 0.0
Global CurrZoom# = 0.0
Global HeartRate# = 0.0
Global HeartVol# = 0.0

; ----- the statue ----------------------------------------------------
Global N173_Collider%, N173_Obj%, N173_Box%, N173_HitBox%
Global N173_Speed# = 0.38          ; NPCs.ini [SCP-173] speed / 100
Global N173_EnemyX#, N173_EnemyZ#
Global N173_State# = 0.0
Global N173_PrevX#, N173_PrevZ#
Global N173_LastSeen% = 0
Global N173_LastDist# = 0.0
Global N173_Drop# = 0.0
Global N173_SoundChn% = 0
Global N173_Active% = False
Global N173HomeX#, N173HomeY#, N173HomeZ#

; ----- multiplayer target --------------------------------------------
; -1 = nobody, 0 = the host player, >0 = a remote player's TCP stream.
; 173 keeps its last target until it loses sight of him, instead of
; instantly switching to whoever happens to be closest.
Global N173_TargetStream% = -1
Global N173_TargetX# = 0.0, N173_TargetZ# = 0.0
; True only while the current target is in clear line of sight: then 173
; walks straight at him; without LOS it must use the A* route.
Global N173_DirectChase% = False
; How much room the statue needs when raycasting. Small enough to squeeze
; through the map's very narrow gaps; temporarily halved in "squeeze mode".
Global N173_Clearance# = 0.12
Global N173_SqueezeFrames% = 0

; ----- pathfinding ----------------------------------------------------
; 1 unit grid over the playable box. A*) gives 173 a real route around
; walls instead of letting it walk straight into geometry.
Const N173_GX0% = -31, N173_GZ0% = -12, N173_GNX% = 37, N173_GNZ% = 17
Const N173_NODES% = 629
Dim N173WalkCache%(629)
Dim N173PrevNode%(629)
Dim N173Queue%(640)
Dim N173PathNodes%(640)
Global N173PathLen% = 0, N173PathIdx% = 0, N173PathGoal% = -1
Global N173PathTimer% = 0

; ----- containment door ---------------------------------------------
Global DoorPivot%, DoorLeft%, DoorRight%
Global DoorOpenState# = 0.0
Global DoorOpened% = False

; ----- sounds --------------------------------------------------------
Global StoneDragSnd%, DoorOpen173Snd%, ButtonSnd%, DamageSnd%
Dim RattleSnd%(2)
Dim NeckSnapSnd%(2)
Dim HorrorSnd%(1)
Dim StepSnd%(7)
Dim RunSnd%(7)

; ----- doors ---------------------------------------------------------
Dim CellDoor%(31)
Dim CellBtn%(31)
Dim CellDoorOpen%(31)
Dim CellDoorInit%(31)
Dim CellDoorState#(31)
Dim CellDoorHasBtn%(31)
Dim CellDoorX#(31), CellDoorY#(31), CellDoorZ#(31), CellDoorAngle%(31)
Global DoorTemplate%, DoorFrameTemplate%, Door02Tex%
Global CellDoorCount% = 0
Global StepDist# = 0.0
Global TouchMsgTimer# = 0.0

; ----- death / messages ---------------------------------------------
Global KillTimer# = 0.0
Global DeathMSG$ = ""
Global DeathAnim% = 0
Global HintMSG$ = ""
Global HintTimer# = 0.0

; =====================================================================
; LoadAssets - only 173's model, textures and sounds are loaded
; =====================================================================
; 2D positional loop sound (replacement of the original LoopSound2)
Function LoopSound2(snd%, chn%, listener%, origin%, range#, vol#)
	Local d# = EntityDistance(listener, origin)
	Local v# = vol * Max(0.0, 1.0 - d / range) * SFXVolume
	If chn = 0 Or (Not ChannelPlaying(chn)) Then
		chn = PlaySound(snd)
	End If
	If chn <> 0 Then ChannelVolume chn, v
	Return chn
End Function

Function LoadAssets()
	Local i%
	BlinkMeterIMG = LoadImage("GFX\BlinkMeter.jpg")
	ResizeImage BlinkMeterIMG, 10 * MenuScale, 14 * MenuScale

	StoneDragSnd   = LoadSound("SFX\SCP\173\StoneDrag.ogg")
	DoorOpen173Snd = LoadSound("SFX\Door\DoorOpen173.ogg")
	ButtonSnd      = LoadSound("SFX\Interact\Button.ogg")
	DamageSnd      = LoadSound("SFX\Horror\Horror1.ogg")
	For i = 0 To 2
		RattleSnd(i)   = LoadSound("SFX\SCP\173\Rattle" + (i + 1) + ".ogg")
		NeckSnapSnd(i) = LoadSound("SFX\SCP\173\NeckSnap" + (i + 1) + ".ogg")
	Next
	HorrorSnd(0) = LoadSound("SFX\Horror\Horror2.ogg")
	HorrorSnd(1) = LoadSound("SFX\Horror\Horror3.ogg")
	For i = 0 To 7
		StepSnd(i) = LoadSound("SFX\Step\Step" + (i + 1) + ".ogg")
		RunSnd(i)  = LoadSound("SFX\Step\Run" + (i + 1) + ".ogg")
	Next
End Function

; =====================================================================
; BuildWorld - the original first spawn map (173bright) and its cells
; =====================================================================
Function AddRoomLight(x#, y#, z#, r%, g%, b%, range#)
	Local l% = CreateLight(2)
	LightColor l, r, g, b
	LightRange l, range
	PositionEntity l, x, y, z
	Return l
End Function

; exactly mirrors CreateDoor() + FillRoom Case "173" from the original
Function CreateCellDoor(x#, y#, z#, angle%, withBtn%, cellTex%, initOpen% = False)
	Local i% = CellDoorCount
	If i > 30 Then Return

	CellDoor(i) = CopyEntity(DoorTemplate)
	ScaleEntity CellDoor(i), (204.0 * RoomScale) / MeshWidth(CellDoor(i)), (312.0 * RoomScale) / MeshHeight(CellDoor(i)), (16.0 * RoomScale) / MeshDepth(CellDoor(i))
	PositionEntity CellDoor(i), x, y, z
	RotateEntity CellDoor(i), 0, angle, 0
	EntityType CellDoor(i), HIT_MAP
	EntityPickMode CellDoor(i), 2
	If cellTex And Door02Tex <> 0 Then EntityTexture CellDoor(i), Door02Tex

	Local frame% = CopyEntity(DoorFrameTemplate)
	ScaleEntity frame, RoomScale, RoomScale, RoomScale
	PositionEntity frame, x, y, z
	RotateEntity frame, 0, angle, 0
	EntityType frame, HIT_MAP

	CellDoorX(i) = x : CellDoorY(i) = y : CellDoorZ(i) = z : CellDoorAngle(i) = angle
	CellDoorOpen(i) = initOpen
	CellDoorInit(i) = initOpen
	CellDoorState(i) = 0

	If withBtn Then
		CellDoorHasBtn(i) = 1
		CellBtn(i) = CreateCube()
		ScaleEntity CellBtn(i), 0.13, 0.13, 0.13
		PositionEntity CellBtn(i), x + 0.85, 0.95, z
		EntityColor CellBtn(i), 255, 170, 60
		EntityPickMode CellBtn(i), 2
	Else
		CellDoorHasBtn(i) = 0
		CellBtn(i) = 0
	End If

	CellDoorCount = CellDoorCount + 1
End Function

; mirrors the original UpdateDoors() dir=0 sliding animation
Function UpdateCellDoors()
	For i = 0 To CellDoorCount - 1
		; cell doors stay closed until opened with their button
		If CellDoorHasBtn(i) = 1 And CellBtn(i) <> 0 Then
			If EntityDistance(Collider, CellBtn(i)) < 1.5 Then
				If KeyHit(18) Then
					CellDoorOpen(i) = 1 - CellDoorOpen(i)
					PlaySound ButtonSnd
				End If
				If HintTimer < 60 Then
					HintMSG = "Press E to open / close the door"
					HintTimer = 60
				End If
			End If
		End If

		If CellDoor(i) <> 0 Then
			If CellDoorOpen(i) Then
				If CellDoorState(i) < 180 Then
					CellDoorState(i) = Min(180, CellDoorState(i) + FPSfactor * 2)
					MoveEntity CellDoor(i), Sin(CellDoorState(i)) * FPSfactor / 80.0, 0, 0
				End If
			Else
				If CellDoorState(i) > 0 Then
					CellDoorState(i) = Max(0, CellDoorState(i) - FPSfactor * 2)
					MoveEntity CellDoor(i), -Sin(CellDoorState(i)) * FPSfactor / 80.0, 0, 0
				End If
			End If
		End If
	Next
End Function

Function BuildWorld()
	; ---- the map: "Class-D Cells & SCP-173's chamber in the intro" ----
	RoomObj = LoadRoomMesh("GFX\map\173bright_opt.rmesh")
	ScaleEntity RoomObj, RoomScale, RoomScale, RoomScale
	PositionEntity RoomObj, 0, 0, 0

	; ---- lights (the point entities of the original rmesh) ------------
	; lightmaps carry most of the baked lighting, so keep the dynamic
	; lights subtle to avoid washing the whole map out
	AmbientLight 55, 55, 62
	AddRoomLight -18.0, 2.4,  1.0, 150, 155, 170, 14
	AddRoomLight -13.0, 2.4,  1.4, 160, 165, 180, 13

	; the statue prop that sits in the chamber in the real game
	Local prop% = LoadMesh("GFX\map\173_2.b3d")
	If prop <> 0 Then
		PositionEntity prop, 5.7, 0.0, 4.6, True
		EntityType prop, HIT_MAP
		EntityPickMode prop, 2
	End If

	; ---- player -------------------------------------------------------
	Collider = CreatePivot()
	EntityRadius Collider, 0.15, 0.30
	EntityType Collider, HIT_PLAYER
	EntityPickMode Collider, 1

	Camera = CreateCamera()
	CameraRange Camera, 0.05, 90.0
	CameraFogMode Camera, 1
	CameraFogRange Camera, 6.0, 48.0
	CameraFogColor Camera, 16, 18, 22   ; cold concrete haze, like the FPS example

	; visual effects: headlamp, flickering lights
	FX_Build()

	; P90 viewmodel (hidden until inventory slot 1 is pressed)
	P90_Build()

	; The original intro room has a few gaps between cell / hall / chamber
	; collision meshes. A continuous invisible floor makes sure the player
	; can always walk from the cell to SCP-173's chamber.
	Local safeFloor% = CreateMesh()
	Local safeSurf% = CreateSurface(safeFloor)
	AddVertex safeSurf, -45, 0, -25
	AddVertex safeSurf,  45, 0, -25
	AddVertex safeSurf,  45, 0,  25
	AddVertex safeSurf, -45, 0,  25
	; both windings -> double sided, so collision works from above
	AddTriangle safeSurf, 0, 1, 2
	AddTriangle safeSurf, 0, 2, 1
	AddTriangle safeSurf, 0, 2, 3
	AddTriangle safeSurf, 0, 3, 2
	EntityAlpha safeFloor, 0.0
	EntityType safeFloor, HIT_MAP
	EntityPickMode safeFloor, 2

	; invisible walls around the playable area
	Local safeWall% = CreateMesh()
	Local ws% = CreateSurface(safeWall)
	AddVertex ws, -45, 0, -25 : AddVertex ws, 45, 0, -25 : AddVertex ws, 45, 8, -25 : AddVertex ws, -45, 8, -25
	AddTriangle ws, 0, 1, 2 : AddTriangle ws, 0, 2, 1
	AddTriangle ws, 0, 2, 3 : AddTriangle ws, 0, 3, 2
	EntityAlpha safeWall, 0.0
	EntityType safeWall, HIT_MAP
	EntityPickMode safeWall, 2

	Collisions HIT_PLAYER, HIT_MAP, 2, 2
	; the player can bump into 173's sensor hitbox, but 173 is repositioned
	; every frame so it can never be pushed around
	Collisions HIT_PLAYER, HIT_173, 1, 2

	; ---- SCP-173 (CreateNPC / Case NPCtype173, NPCs.bb 84-112) --------
	N173_Collider = CreatePivot()
	EntityRadius N173_Collider, 0.23, 0.32
	EntityType N173_Collider, HIT_PLAYER

	N173_Obj = LoadMesh("GFX\npcs\173_2.b3d")
	Local s# = INI_Float("Data\NPCs.ini", "SCP-173", "scale", 0.35) / MeshDepth(N173_Obj)
	ScaleEntity N173_Obj, s, s, s
	N173_Speed = INI_Float("Data\NPCs.ini", "SCP-173", "speed", 38.0) / 100.0

	; the .b3d stores an absolute texture path from the developer's PC,
	; so apply the original texture explicitly - otherwise 173 is black
	Local tex173% = LoadTexture("GFX\npcs\173texture.jpg")
	If tex173 <> 0 Then EntityTexture N173_Obj, tex173, 0, 0
	EntityFX N173_Obj, 1   ; fullbright: always show the real colours

	; sensor hitbox - no collision response, so it can be touched but
	; can never be pushed around by the player
	N173_HitBox = CreatePivot()
	EntityRadius N173_HitBox, 0.38, 0.5
	EntityType N173_HitBox, HIT_173
	EntityParent N173_HitBox, N173_Collider

	N173_Box = LoadMesh("GFX\173box.b3d")
	ScaleEntity N173_Box, RoomScale, RoomScale, RoomScale
	HideEntity N173_Box

	; ---- containment door (CreateDoor big=1) --------------------------
	Local doorMeshL% = LoadMesh("GFX\map\ContDoorLeft.x")
	Local doorMeshR% = LoadMesh("GFX\map\ContDoorRight.x")
	Local doorFrame% = LoadMesh("GFX\map\DoorColl.x")

	DoorLeft = CopyEntity(doorMeshL)
	ScaleEntity DoorLeft, 55 * RoomScale, 55 * RoomScale, 55 * RoomScale
	EntityType DoorLeft, HIT_MAP
	EntityPickMode DoorLeft, 2
	DoorRight = CopyEntity(doorMeshR)
	ScaleEntity DoorRight, 55 * RoomScale, 55 * RoomScale, 55 * RoomScale
	EntityType DoorRight, HIT_MAP
	EntityPickMode DoorRight, 2

	DoorPivot = CreatePivot()
	PositionEntity DoorPivot, 288 * RoomScale, 0, 384 * RoomScale
	EntityAlpha doorFrame, 0.0
	ScaleEntity doorFrame, RoomScale, RoomScale, RoomScale
	EntityType doorFrame, HIT_MAP

	PositionEntity DoorLeft,  288 * RoomScale, 0, 384 * RoomScale
	RotateEntity DoorLeft, 0, 90, 0
	PositionEntity DoorRight, 288 * RoomScale, 0, 384 * RoomScale
	RotateEntity DoorRight, 0, 90, 0

	FreeEntity doorMeshL
	FreeEntity doorMeshR
	FreeEntity doorFrame

	; ---- doors, exact positions from FillRoom Case "173" (MapSystem.bb) ----
	DoorTemplate = LoadMesh("GFX\map\Door01.x")
	DoorFrameTemplate = LoadMesh("GFX\map\DoorFrame.x")
	Door02Tex = LoadTexture("GFX\map\Door02.jpg")
	CellDoorCount = 0

	; only cell doors exist - every other door from the original room has
	; been deliberately left out of this mini game

	; cell block - the two loops from the original source
	Local ztemp%, xtemp%, z1#, z2#, ang%
	For ztemp = 0 To 1
		z1 = (320 + 896 * ztemp) * RoomScale
		z2 = (1008 - 480 * ztemp) * RoomScale
		ang = 180 * (Not ztemp)
		CreateCellDoor -5760 * RoomScale, 0, z1, 0, False, False
		CreateCellDoor -8288 * RoomScale, 0, z1, 0, False, False, (ztemp = 0)
		For xtemp = 0 To 2
			CreateCellDoor -(7424.0 - 512.0 * xtemp) * RoomScale, 0, z2, ang, False, True, False
		Next
		For xtemp = 0 To 4
			; ONLY the spawn cell (xtemp=2, ztemp=1) starts open, with a
			; button. Every other cell door stays closed like the real game.
			CreateCellDoor -(5120.0 - 512.0 * xtemp) * RoomScale, 0, z2, ang, (xtemp = 2 And ztemp = 1), True, (xtemp = 2 And ztemp = 1)
		Next
	Next
	FreeEntity DoorTemplate
	FreeEntity DoorFrameTemplate

	; ---- spawn points from the original "173" room --------------------
	SpawnX = -4096 * RoomScale : SpawnY = 0.3 : SpawnZ = 192 * RoomScale
	N173HomeX = 1472 * RoomScale : N173HomeY = 0.35 : N173HomeZ = 912 * RoomScale

	; specular layers + the real-time cube map reflected by the floor
	RF_Init()

	ResetRound()
	; rebuild the remote player figures on the new world
	NetRebuildAvatars()
End Function

; =====================================================================
; ResetRound - (re)spawn player + statue at the beginning / after death
; =====================================================================
Function ResetRound()
	PositionEntity Collider, SpawnX, SpawnY, SpawnZ, True
	ResetEntity Collider
	RotateEntity Collider, 0, -90, 0, True   ; face towards the chamber (+X)
	UserPitch = 0 : PlayerSpeed = 0 : DropSpeed = 0

	PositionEntity N173_Collider, N173HomeX, N173HomeY, N173HomeZ, True
	ResetEntity N173_Collider
	N173_Active = False
	N173_EnemyX = 0 : N173_EnemyZ = 0
	N173_TargetStream = -1 : N173_TargetX = 0 : N173_TargetZ = 0
	N173PathLen = 0 : N173PathIdx = 0 : N173PathGoal = -1 : N173PathTimer = 0
	N173ClearPathCache()
	N173_SqueezeFrames = 0
	N173_State = 0 : N173_PrevX = N173HomeX : N173_PrevZ = N173HomeZ
	N173_LastDist = 0 : N173_Drop = 0
	HideEntity N173_Box

	PositionEntity DoorLeft,  288 * RoomScale, 0, 384 * RoomScale
	PositionEntity DoorRight, 288 * RoomScale, 0, 384 * RoomScale
	DoorOpenState = 0 : DoorOpened = False

	BlinkTimer = -10.0
	BLINKFREQ  = Rnd(490, 700)
	BlurVolume = 0 : CurrZoom = 0
	HeartRate = 0 : HeartVol = 0
	KillTimer = 0 : DeathMSG = ""
	FX_Reset()
	P90_Reset()
	HintMSG = "You are D-9341. Leave the cell and reach SCP-173's chamber."
	HintTimer = 600

	For i = 0 To CellDoorCount - 1
		CellDoorOpen(i) = CellDoorInit(i)
		CellDoorState(i) = 0
		If CellDoor(i) <> 0 Then
			PositionEntity CellDoor(i), CellDoorX(i), CellDoorY(i), CellDoorZ(i), True
			RotateEntity CellDoor(i), 0, CellDoorAngle(i), 0
			ResetEntity CellDoor(i)
		End If
	Next
	StepDist = 0

	GameState = GS_PLAY
	CameraZoom Camera, 1.0
	HidePointer()
	MoveMouse GraphW / 2, GraphH / 2
	FlushKeys()
	FlushMouse()
End Function

; SCP-173 must never leave the playable area or sink through the floor.
; Its AI teleports it and collision response can push it out, so it is
; hard-clamped back into the safe box on every single frame.
Function Keep173InBounds()
	If N173_Collider = 0 Then Return
	Local x# = Clamp(EntityX(N173_Collider, True), -32.0, 6.0)
	Local z# = Clamp(EntityZ(N173_Collider, True), -13.0, 5.0)
	PositionEntity N173_Collider, x, 0.35, z, True
	ResetEntity N173_Collider

	; emergency recovery: if collision response ever pushed the statue into
	; geometry, move it to the closest open grid cell instead of leaving it
	; stuck inside a wall
	If N173Embedded() = 0 Then Return
	Local sx% = Int(x - N173_GX0 + 0.5), sz% = Int(z - N173_GZ0 + 0.5)
	Local r%, dx%, dz%, node%
	For r = 0 To 4
		For dz = -r To r
			For dx = -r To r
				If Abs(dx) = r Or Abs(dz) = r Then
					node = N173NodeIndex(sx + dx, sz + dz)
					If node >= 0 And N173NodeOpen(node) Then
						PositionEntity N173_Collider, N173NodeX(node), 0.35, N173NodeZ(node), True
						ResetEntity N173_Collider
						Return
					End If
				End If
			Next
		Next
	Next
End Function

; Very short 360 degree probe: fewer than two clear directions means the
; collider is embedded in geometry and must be rescued.
Function N173Embedded%()
	If N173_Collider = 0 Then Return False
	Local x# = EntityX(N173_Collider, True), z# = EntityZ(N173_Collider, True)
	Local clear% = 0, i%
	For i = 0 To 7
		Local a# = i * 45.0
		If N173RayClearFrom(x, z, Sin(a), Cos(a), 0.35) Then clear = clear + 1
	Next
	Return clear < 2
End Function

; collision response in UpdateWorld() can push both colliders down into
; the floor, so re-assert their height every frame after the world update
Function LockHeights()
	If Collider <> 0 Then
		PositionEntity Collider, Clamp(EntityX(Collider), -33.0, 7.0), 0.3, Clamp(EntityZ(Collider), -14.0, 6.0), True
	End If
	Keep173InBounds()
End Function

; =====================================================================
; Blink - faithful port of the blink timer from Main.bb
; BlinkTimer > -6 / < -16 : eyes open or opening -> 173 is frozen in view
; BlinkTimer -16 .. -6    : eyes closed -> 173 may move
; =====================================================================
Function UpdateBlink()
	If BlinkTimer < 0 Then
		If BlinkTimer <= -20 Then
			BLINKFREQ = Rnd(455, 700)
			BlinkTimer = BLINKFREQ
		End If
		BlinkTimer = BlinkTimer - FpsFactor
	Else
		BlinkTimer = BlinkTimer - FpsFactor * 0.6
	End If

	; manual blink (KEY_BLINK from options.ini, Space by default)
	If KeyHit(BlinkKey) Then
		BlinkTimer = 0
		FX_OnBlink()
	End If
	If KeyDown(BlinkKey) And BlinkTimer < -10 Then BlinkTimer = -10
End Function

Function BlinkDarkness#()
	If BlinkTimer >= 0 Then Return 0.0
	If BlinkTimer > -5 Then Return Abs(BlinkTimer) / 5.0
	If BlinkTimer > -15 Then Return 1.0
	If BlinkTimer > -20 Then Return Abs(BlinkTimer + 20.0) / 5.0
	Return 0.0
End Function

; =====================================================================
; Player input and movement
; =====================================================================
Function UpdatePlayer()
	Local i%, sens# = (MouseSens + 1.0) * 0.3 * FpsFactor
	Local px# = EntityX(Collider), pz# = EntityZ(Collider)
	Local mx# = MouseXSpeed() * sens
	Local my# = MouseYSpeed() * sens
	If InvertMouse Then my = -my

	TurnEntity Collider, 0, -mx, 0
	UserPitch = Clamp(UserPitch + my, -70.0, 70.0)

	; --- WASD with real 8-way movement ---
	; W+D = forward-right, W+A = forward-left, S+D = back-right, ...
	Local angle# = 0.0, moving% = False
	If KeyDown(17) Then
		angle = 0 : moving = True
	ElseIf KeyDown(31) Then
		angle = 180 : moving = True
	End If
	If KeyDown(30) Then                       ; A / left
		If moving Then
			If angle = 180 Then angle = angle - 45 Else angle = angle + 45
		Else
			angle = 90 : moving = True
		End If
	End If
	If KeyDown(32) Then                       ; D / right
		If moving Then
			If angle = 180 Then angle = angle + 45 Else angle = angle - 45
		Else
			angle = -90 : moving = True
		End If
	End If

	Local target# = 0.0
	If moving Then
		target = 0.022
		If KeyDown(42) Then target = 0.038     ; Shift = sprint
		If KeyDown(29) Then target = target * 0.55 ; Ctrl = crouch
	End If
	PlayerSpeed = Curve(target, PlayerSpeed, 12.0)

	If moving Then
		Local a# = WrapAngle(EntityYaw(Collider, True) + angle + 90.0)
		TranslateEntity Collider, Cos(a) * PlayerSpeed * FpsFactor, 0, Sin(a) * PlayerSpeed * FpsFactor, True
		StepBob = StepBob + 0.18 * FpsFactor
	End If

	; --- footsteps -----------------------------------------------------
	If moving Then
		StepDist = StepDist + Distance(px, pz, EntityX(Collider), EntityZ(Collider))
		Local stride# = 0.85
		If KeyDown(42) Then stride = 1.25
		If StepDist >= stride Then
			StepDist = 0
			If KeyDown(42) Then
				PlaySound RunSnd(Rand(0, 7))
			Else
				PlaySound StepSnd(Rand(0, 7))
			End If
		End If
	Else
		StepDist = 0.7
	End If

	; --- keep the player on the floor and inside the map -----------------
	PositionEntity Collider, EntityX(Collider), 0.3, EntityZ(Collider), True
	PositionEntity Collider, Clamp(EntityX(Collider), -33.0, 7.0), 0.3, Clamp(EntityZ(Collider), -14.0, 6.0), True
	DropSpeed = 0

	; --- camera follows the collider (Main.bb 4400-4403) ---------------
	PositionEntity Camera, EntityX(Collider), EntityY(Collider), EntityZ(Collider)
	RotateEntity Camera, UserPitch, EntityYaw(Collider), 0
	MoveEntity Camera, 0, 0.6 + Sin(StepBob) * 0.006 * Abs(PlayerSpeed) / 0.022, 0

	; keep the mouse captured in the middle of the window
	MoveMouse GraphW / 2, GraphH / 2
End Function

; =====================================================================
; Containment door - opens when the player reaches the chamber.
; The exact instant it opens is the breach: SCP-173 becomes active.
; =====================================================================
Function UpdateDoor()
	If Not DoorOpened Then
		Local d# = EntityDistance(Collider, DoorPivot)
		; the player walking up to the chamber door (or pressing E) breaches it
		If d < 9.0 Or KeyHit(18) And d < 12.0 Then
			DoorOpened = True
			N173_Active = True
			N173ClearPathCache()
			PlaySound DoorOpen173Snd
			FX_OnBreach()
			HintMSG = "CONTAINMENT BREACH - SCP-173 IS LOOSE. DO NOT BLINK."
			HintTimer = 450
		End If
	End If

	If DoorOpened Then
		If DoorOpenState < 1.0 Then
			DoorOpenState = Min(1.0, DoorOpenState + FpsFactor * 0.012)
			; slide the two panels fully apart along the doorway (Z axis),
			; instead of relying on incremental MoveEntity offsets
			PositionEntity DoorLeft,  288 * RoomScale, 0, 384 * RoomScale - 2.2 * DoorOpenState
			PositionEntity DoorRight, 288 * RoomScale, 0, 384 * RoomScale + 2.2 * DoorOpenState
			ResetEntity DoorLeft
			ResetEntity DoorRight
		End If
	End If
End Function

; =====================================================================
; SCP-173 AI - the real mechanic (NPCs.bb 683-884)
; =====================================================================
Function Update173()
	If Not N173_Active Then
		Keep173InBounds()
		PositionEntity N173_Obj, EntityX(N173_Collider), 0.03, EntityZ(N173_Collider)
		RotateEntity N173_Obj, 0, EntityYaw(N173_Collider) - 180, 0
		Return
	End If

	If N173_SqueezeFrames > 0 Then N173_SqueezeFrames = N173_SqueezeFrames - 1

	; offline always chases the local player, with a live target position
	If NetMode = NET_OFFLINE Then
		N173_TargetStream = 0
		N173_TargetX = EntityX(Collider, True) : N173_TargetZ = EntityZ(Collider, True)
	End If

	; distance to the CURRENT target (not simply the closest player)
	Local tx0# = N173_TargetX, tz0# = N173_TargetZ
	If N173_TargetStream <= 0 Then
		tx0 = EntityX(Collider, True) : tz0 = EntityZ(Collider, True)
	End If
	N173_DirectChase = False
	If N173_TargetStream >= 0 Then N173_DirectChase = N173LOSClearTo(tx0, tz0)
	Local dist# = Distance(EntityX(N173_Collider, True), EntityZ(N173_Collider, True), tx0, tz0)

	; proximity drives the red danger vignette and the FOV tightening
	FX_Danger = Curve(Clamp((4.5 - dist) / 4.5, 0.0, 1.0), FX_Danger, 10.0)

	; the sensor hitbox lets you get right up to it, but it can never be
	; pushed - the player simply walks through the invisible sensor volume
	If dist < 1.0 Then
		If HintTimer < 60 Then
			HintMSG = "SCP-173 is right there - DO NOT TOUCH"
			HintTimer = 60
		End If
		If Rand(120) = 1 Then PlaySound RattleSnd(Rand(0, 2))
	End If

	; line of sight -> last known target position (NPCs.bb 704-714)
	Local canSee% = N173CanSeeTarget()
	If canSee Then
		N173_EnemyX = N173_TargetX
		N173_EnemyZ = N173_TargetZ
	End If

	; movement volume / rolling stone sound (NPCs.bb 716-720)
	Local soundVol# = Max(Min(Distance(EntityX(N173_Collider), EntityZ(N173_Collider), N173_PrevX, N173_PrevZ) * 2.5, 1.0), 0.0)
	N173_SoundChn = LoopSound2(StoneDragSnd, N173_SoundChn, Camera, N173_Collider, 10.0, N173_State)
	N173_PrevX = EntityX(N173_Collider)
	N173_PrevZ = EntityZ(N173_Collider)

	; *** THE OBSERVATION CHECK ***
	; Eyes open (or opening) AND the statue inside the view frustum
	; => it cannot move. During a blink it can. In multiplayer ANY player
	; watching it freezes the statue - all observers must look away.
	Local move% = True
	If (BlinkTimer < -16 Or BlinkTimer > -6) Then
		If EntityInView(N173_Obj, Camera) Then move = False
	End If
	If NetMode = NET_HOST And NetRemoteWatching <> 0 Then move = False

	If move = False Then
		; --- being watched: blur, zoom, heartbeat (NPCs.bb 730-756) ---
		; keep the "being watched" effect very subtle - no flickering
		BlurVolume = Max(Min((4.0 - dist) / 12.0, 0.35), BlurVolume)
		CurrZoom = Max(CurrZoom, (Sin(Float(MilliSecs()) / 20.0) + 1.0) * 2.5 * Max((3.5 - dist) / 3.5, 0.0))
		If dist < 3.5 And MilliSecs() - N173_LastSeen > 60000 And canSee Then
			PlaySound HorrorSnd(Rand(0, 1))
			FX_Shake 0.28
			N173_LastSeen = MilliSecs()
		End If
		If dist < 1.5 And Rand(700) = 1 Then PlaySound RattleSnd(Rand(0, 2))
		If dist < 1.5 And N173_LastDist > 2.0 And canSee And Rand(3) = 1 Then
			CurrZoom = 18.0
			HeartRate = Max(HeartRate, 140)
			HeartVol = 0.5
			PlaySound DamageSnd
		End If
		N173_LastDist = dist
		N173_State = Max(0.0, N173_State - FpsFactor / 20.0)
	Else
		; --- free to move ----------------------------------------------
		; It may also move behind walls and out of sight - that is the whole
		; point of SCP-173. All movement is raycast based, so it finds a
		; sensible path around walls and never leaves the map.

		; short "blink" jumps, like the original, but validated: no jumping
		; through walls and no landing inside geometry / outside the map
		If dist > 45 Then
			If Rand(90) = 1 Then
				N173TryRelocate tx0 - 14.0, tz0 + Rnd(-8, 8)
			End If
		ElseIf dist > 12 Then
			If Rand(120) = 1 Then
				N173TryRelocate EntityX(N173_Collider, True) + Rnd(-6, 6), EntityZ(N173_Collider, True) + Rnd(-6, 6)
			End If
		End If
		N173_State = Curve(soundVol, N173_State, 3.0)

		If canSee And dist < 0.65 Then
			; the kill (NPCs.bb 827-860)
			If KillTimer >= 0 Or N173_TargetStream > 0 Then
				If N173_TargetStream > 0 Then
					; the victim is a remote player - tell him, don't kill the host
					NetKillRemote(N173_TargetStream)
					N173HideInCorner()
				Else
					DeathMSG = "Subject D-9341. Cause of death: Fatal cervical fracture. The surveillance tapes confirm that the subject was killed by SCP-173."
					PlaySound NeckSnapSnd(Rand(0, 2))
					If Rand(2) = 1 Then
						TurnEntity Camera, 0, Rand(80, 100), 0
					Else
						TurnEntity Camera, 0, Rand(-100, -80), 0
					End If
					KillPlayer()
				End If
			End If
		Else
			; walk towards the current target (or the last place it saw him)
			; using 360 degree raycasts, so walls never stop it permanently
			Local tx#, tz#
			If canSee Then
				N173_EnemyX = N173_TargetX
				N173_EnemyZ = N173_TargetZ
			End If
			If N173_EnemyX <> 0 Then
				tx = N173_EnemyX : tz = N173_EnemyZ
				If Distance(EntityX(N173_Collider, True), EntityZ(N173_Collider, True), tx, tz) < 0.8 Then
					N173_EnemyX = 0 : N173_EnemyZ = 0
					tx = tx0 : tz = tz0
				End If
			Else
				tx = tx0 : tz = tz0
			End If
			N173SteerTo tx, tz
		End If
	End If

	; keep the statue locked to the floor and inside the map
	Keep173InBounds()

	PositionEntity N173_Obj, EntityX(N173_Collider), 0.03, EntityZ(N173_Collider)
	RotateEntity N173_Obj, 0, EntityYaw(N173_Collider) - 180, 0
End Function

; =====================================================================
; Multiplayer targeting - 173 sticks to a target instead of always
; switching to whoever is closest.
; =====================================================================
; A remote player disconnected: drop him as a target and forget his path.
Function N173RejectTarget(stream%)
	If N173_TargetStream = stream% Then
		N173_TargetStream = -1
		N173_EnemyX = 0 : N173_EnemyZ = 0
		N173PathLen = 0 : N173PathIdx = 0 : N173PathGoal = -1
		N173ClearPathCache()
	End If
End Function

Function N173PlayerExists%(stream%)
	If stream% < 0 Then Return False
	If stream% = 0 Then Return True
	Local rp.RemotePlayer
	For rp = Each RemotePlayer
		If rp\stream = stream% Then Return True
	Next
	Return False
End Function

Function N173PlayerX#(stream%)
	If stream% <= 0 Then Return EntityX(Collider, True)
	Local rp.RemotePlayer
	For rp = Each RemotePlayer
		If rp\stream = stream% Then Return rp\x
	Next
	Return N173_TargetX
End Function

Function N173PlayerZ#(stream%)
	If stream% <= 0 Then Return EntityZ(Collider, True)
	Local rp.RemotePlayer
	For rp = Each RemotePlayer
		If rp\stream = stream% Then Return rp\z
	Next
	Return N173_TargetZ
End Function

Function N173PlayerWatching%(stream%)
	If stream% <= 0 Then Return 0
	Local rp.RemotePlayer
	For rp = Each RemotePlayer
		If rp\stream = stream% Then Return Abs(rp\observing)
	Next
	Return 0
End Function

; Full-length line of sight to a point (no artificial 10-unit limit).
Function N173LOSClearTo%(x#, z#)
	Local cx# = EntityX(N173_Collider, True), cz# = EntityZ(N173_Collider, True)
	Local dx# = x - cx, dz# = z - cz
	Local dl# = Sqr(dx * dx + dz * dz)
	If dl < 0.35 Then Return True
	Return N173RayClearFrom(cx, cz, dx / dl, dz / dl, dl)
End Function

; Can 173 see this player from where it stands right now?
Function N173SeePlayer%(stream%, x#, z#)
	If stream% = -1 Then Return False
	Local cx# = EntityX(N173_Collider, True), cz# = EntityZ(N173_Collider, True)
	If Distance(cx, cz, x, z) > 10.0 Then Return False
	If stream% <= 0 Then Return EntityVisible(N173_Collider, Collider)
	Local dx# = x - cx, dz# = z - cz
	Local dl# = Sqr(dx * dx + dz * dz)
	If dl < 0.35 Then Return True
	Return N173RayClearFrom(cx, cz, dx / dl, dz / dl, dl)
End Function

Function N173CanSeeTarget%()
	If N173_TargetStream = -1 Then Return False
	Return N173SeePlayer(N173_TargetStream, N173_TargetX, N173_TargetZ)
End Function

; pick a random player (never "the nearest one") when there is no target yet
Function N173PickAnyPlayer%()
	Local total% = 1, pick%, idx% = 1
	Local rp.RemotePlayer
	For rp = Each RemotePlayer
		If rp\stream > 0 Then total = total + 1
	Next
	pick = Rand(0, total - 1)
	If pick = 0 Then Return 0
	For rp = Each RemotePlayer
		If rp\stream > 0 Then
			If idx = pick Then Return rp\stream
			idx = idx + 1
		End If
	Next
	Return 0
End Function

Function N173ChooseTarget()
	If Not N173_Active Then Return
	Local cur% = N173_TargetStream
	If Not N173PlayerExists(cur) Then cur = -1
	If cur = -1 Then cur = N173PickAnyPlayer()

	Local px# = N173PlayerX(cur), pz# = N173PlayerZ(cur)
	If N173SeePlayer(cur, px, pz) Then
		; still has the target in sight - keep chasing him
		N173_TargetStream = cur
		N173_TargetX = px : N173_TargetZ = pz
		Return
	End If

	; target lost: look for a visible player, preferring one who is watching
	Local best% = -1
	Local rp.RemotePlayer
	If N173SeePlayer(0, N173PlayerX(0), N173PlayerZ(0)) Then best = 0
	For rp = Each RemotePlayer
		If rp\stream > 0 Then
			If N173SeePlayer(rp\stream, rp\x, rp\z) Then
				If N173PlayerWatching(rp\stream) <> 0 Then best = rp\stream : Exit
				If best = -1 Or Rand(2) = 0 Then best = rp\stream
			End If
		End If
	Next
	If best <> -1 Then cur = best
	N173_TargetStream = cur
	; update the coordinates only while he is actually seen: otherwise 173
	; keeps chasing the last known position, like in the single player game
	If N173SeePlayer(cur, N173PlayerX(cur), N173PlayerZ(cur)) Then
		N173_TargetX = N173PlayerX(cur) : N173_TargetZ = N173PlayerZ(cur)
	End If
End Function

; =====================================================================
; SCP-173 navigation - 360 degree raycasts
; =====================================================================
; One ray bundle from a world point. The statue has a body, not a single
; point: cast the centre line plus two parallel lines at its sides, starting
; AT the current position (never 0.3 units ahead, which skipped walls right
; next to it). Returns True only when the whole body fits through.
Function N173RayClearFrom%(x#, z#, dx#, dz#, dist#)
	Local dl# = Sqr(dx * dx + dz * dz)
	If dl < 0.001 Or dist < 0.05 Then Return True
	dx = dx / dl : dz = dz / dl
	Local px# = -dz, pz# = dx
	Local off# = N173_Clearance
	If N173_SqueezeFrames > 0 Then off = 0.055
	Local i%, ok% = True
	For i = -1 To 1
		Local ox# = x + px * off * i, oz# = z + pz * off * i
		Local hit% = LinePick(ox, 0.42, oz, dx * dist, 0, dz * dist)
		If hit <> 0 Then
			Local e% = PickedEntity()
			If e <> Collider And e <> N173_Collider And e <> N173_Obj And e <> N173_HitBox And e <> N173_Box Then
				ok = False
				Exit
			End If
		End If
	Next
	Return ok
End Function

Function N173DirClear%(dx#, dz#, dist#)
	Return N173RayClearFrom(EntityX(N173_Collider, True), EntityZ(N173_Collider, True), dx, dz, dist)
End Function

; Is (x,z) an open walkable spot? Narrow corridors matter here: a corridor
; only has two clear sides, so require one clear OPPOSITE pair instead of
; counting all eight directions.
Function N173SpotOpen%(x#, z#)
	; short 360 degree scan: a very narrow corridor may only have a few
	; clear directions, so three out of eight is enough. Movement itself is
	; still validated separately by N173MoveToward.
	Local i%, clear% = 0
	For i = 0 To 7
		Local a# = i * 45.0
		If N173RayClearFrom(x, z, Sin(a), Cos(a), 0.7) Then clear = clear + 1
	Next
	Return clear >= 3
End Function

; Validated relocation ("it moved while you blinked"). Rejects anything
; that would cross a wall or land inside geometry / outside the map.
Function N173TryRelocate%(tx#, tz#)
	If tx < -31.5 Or tx > 5.5 Or tz < -12.5 Or tz > 4.5 Then Return False

	Local ox# = EntityX(N173_Collider, True), oz# = EntityZ(N173_Collider, True)
	If Not N173RayClearFrom(ox, oz, tx - ox, tz - oz, Distance(ox, oz, tx, tz)) Then Return False
	If Not N173SpotOpen(tx, tz) Then Return False

	PositionEntity N173_Collider, tx, 0.35, tz, True
	ResetEntity N173_Collider
	Return True
End Function

; ---------- grid pathfinding (walls are never crossed) ----------------
; The whole playable box is a 1 unit grid. Before a node or an edge is
; used it is raycast checked, so the resulting route cannot pass through
; geometry. N173MoveToward validates every single frame as well.
Function N173NodeIndex%(ix%, iz%)
	If ix < 0 Or ix >= N173_GNX Or iz < 0 Or iz >= N173_GNZ Then Return -1
	Return iz * N173_GNX + ix
End Function

Function N173NodeX#(idx%)
	Return N173_GX0 + (idx Mod N173_GNX)
End Function

Function N173NodeZ#(idx%)
	Return N173_GZ0 + Int(idx / N173_GNX)
End Function

Function N173NodeOpen%(idx%)
	If idx < 0 Or idx >= N173_NODES Then Return False
	If N173WalkCache(idx) = 1 Then Return True
	If N173WalkCache(idx) = 2 Then Return False
	Local ok% = N173SpotOpen(N173NodeX(idx), N173NodeZ(idx))
	If ok Then N173WalkCache(idx) = 1 Else N173WalkCache(idx) = 2
	Return ok
End Function

Function N173NearestOpen%(ix%, iz%)
	Local r%, dx%, dz%, idx%
	For r = 0 To 5
		For dz = -r To r
			For dx = -r To r
				If Abs(dx) = r Or Abs(dz) = r Then
					idx = N173NodeIndex(ix + dx, iz + dz)
					If idx >= 0 And N173NodeOpen(idx) Then Return idx
				End If
			Next
		Next
	Next
	Return -1
End Function

Function N173EdgeClear%(a%, b%)
	Local ax# = N173NodeX(a), az# = N173NodeZ(a)
	Local dx# = N173NodeX(b) - ax, dz# = N173NodeZ(b) - az
	Local d# = Sqr(dx * dx + dz * dz)
	If d < 0.9 Or d > 1.5 Then Return False
	Return N173RayClearFrom(ax, az, dx, dz, d)
End Function

Function N173ClearPathCache()
	Local i%
	For i = 0 To N173_NODES - 1 : N173WalkCache(i) = 0 : Next
	N173PathLen = 0 : N173PathIdx = 0 : N173PathGoal = -1 : N173PathTimer = 0
End Function

Function N173BuildPath(tx#, tz#)
	Local i%
	Local cx# = EntityX(N173_Collider, True), cz# = EntityZ(N173_Collider, True)
	Local sx% = Int(cx - N173_GX0 + 0.5), sz% = Int(cz - N173_GZ0 + 0.5)
	Local gx% = Int(tx - N173_GX0 + 0.5), gz% = Int(tz - N173_GZ0 + 0.5)
	Local start% = N173NearestOpen(sx, sz)
	Local goal% = N173NearestOpen(gx, gz)
	N173PathLen = 0 : N173PathIdx = 0 : N173PathGoal = -1
	If start = -1 Or goal = -1 Then Return

	For i = 0 To N173_NODES - 1 : N173PrevNode(i) = -1 : Next
	Local head% = 0, tail% = 0, cur%, nb%, found% = False
	N173Queue(tail) = start : tail = tail + 1
	N173PrevNode(start) = start
	While head < tail
		cur = N173Queue(head) : head = head + 1
		If cur = goal Then found = True : Exit
		Local k%, ox%, oz%, ok%
		For k = 0 To 7
			ox = 0 : oz = 0
			Select k
				Case 0 : ox = 1
				Case 1 : ox = -1
				Case 2 : oz = 1
				Case 3 : oz = -1
				Case 4 : ox = 1 : oz = 1
				Case 5 : ox = 1 : oz = -1
				Case 6 : ox = -1 : oz = 1
				Case 7 : ox = -1 : oz = -1
			End Select
			nb = N173NodeIndex((cur Mod N173_GNX) + ox, Int(cur / N173_GNX) + oz)
			If nb >= 0 And N173PrevNode(nb) = -1 Then
				ok = N173NodeOpen(nb) And N173EdgeClear(cur, nb)
				; never cut a diagonal corner through a wall: both orthogonal
				; edges out of the current cell must be clear
				If ok And ox <> 0 And oz <> 0 Then
					Local side1% = N173NodeIndex((cur Mod N173_GNX) + ox, Int(cur / N173_GNX))
					Local side2% = N173NodeIndex((cur Mod N173_GNX), Int(cur / N173_GNX) + oz)
					If side1 < 0 Or side2 < 0 Then
						ok = False
					Else
						If N173EdgeClear(cur, side1) = 0 Or N173EdgeClear(cur, side2) = 0 Then ok = False
					End If
				End If
				If ok Then
					N173PrevNode(nb) = cur
					N173Queue(tail) = nb : tail = tail + 1
				End If
			End If
		Next
	Wend
	If Not found Then Return

	; reconstruct start -> goal and reverse it
	Local n% = goal, count% = 0, tmp%
	While n <> start And count < 600
		N173PathNodes(count) = n : count = count + 1
		n = N173PrevNode(n)
		If n < 0 Then Exit
	Wend
	For i = 0 To (count / 2) - 1
		tmp = N173PathNodes(i)
		N173PathNodes(i) = N173PathNodes(count - 1 - i)
		N173PathNodes(count - 1 - i) = tmp
	Next

	; string-pulling: keep only the farthest node reachable by a clear straight
	; line. This removes the grid staircase and gives the shortest safe route.
	Local si% = 0, kept% = 0, j%, lastNode%, ax#, az#, bx#, bz#
	While si < count
		ax = N173NodeX(N173PathNodes(si)) : az = N173NodeZ(N173PathNodes(si))
		If si = 0 Then ax = cx : az = cz
		lastNode = si : j = si + 1
		While j < count And j <= si + 16
			bx = N173NodeX(N173PathNodes(j)) : bz = N173NodeZ(N173PathNodes(j))
			If N173RayClearFrom(ax, az, bx - ax, bz - az, Distance(ax, az, bx, bz)) Then lastNode = j
			j = j + 1
		Wend
		N173PathNodes(kept) = N173PathNodes(lastNode) : kept = kept + 1
		si = lastNode + 1
	Wend
	count = kept

	N173PathLen = count : N173PathIdx = 0 : N173PathGoal = goal : N173PathTimer = 45
	; skip waypoints we are already standing on (no backtracking)
	While N173PathIdx < N173PathLen
		n = N173PathNodes(N173PathIdx)
		If Distance(cx, cz, N173NodeX(n), N173NodeZ(n)) > 0.5 Then Exit
		N173PathIdx = N173PathIdx + 1
	Wend
End Function

; One validated step towards a waypoint. Nothing is moved unless both the
; ray to the destination and the destination itself are wall-free.
Function N173MoveToward%(wx#, wz#)
	Local cx# = EntityX(N173_Collider, True), cz# = EntityZ(N173_Collider, True)
	Local dx# = wx - cx, dz# = wz - cz
	Local dl# = Sqr(dx * dx + dz * dz)
	If dl < 0.001 Then Return True
	Local mv# = N173_Speed * FpsFactor
	If mv > dl Then mv = dl
	dx = dx / dl : dz = dz / dl
	Local nx# = Clamp(cx + dx * mv, -31.5, 5.5)
	Local nz# = Clamp(cz + dz * mv, -12.5, 4.5)
	Local attempt%
	For attempt = 0 To 1
		; if the normal body width does not fit, squeeze through the gap
		If attempt = 1 And N173_SqueezeFrames = 0 Then N173_SqueezeFrames = 14
		If N173RayClearFrom(cx, cz, dx, dz, mv + 0.15) Then
			If N173SpotOpen(nx, nz) Then
				; a ray can miss a thin wall edge, so verify the FINAL spot
				; with a very short 360 degree probe as well
				Local ox# = EntityX(N173_Collider, True), oz# = EntityZ(N173_Collider, True)
				PositionEntity N173_Collider, nx, 0.35, nz, True
				If N173Embedded() Then
					PositionEntity N173_Collider, ox, 0.35, oz, True
					Return False
				End If
				Keep173InBounds()
				; Blitz3D yaw: +Z points along (-Sin yaw, Cos yaw)
				RotateEntity N173_Collider, 0, -ATan2(dx, dz), 0
				Return True
			End If
		End If
		If N173_SqueezeFrames > 0 Then Exit
	Next
	Return False
End Function

; Reactive fallback for the (rare) case where the grid finds no route:
; fan out with rays and take the first clear direction, still validated.
Function N173SteerDirect(tx#, tz#)
	Local cx# = EntityX(N173_Collider, True), cz# = EntityZ(N173_Collider, True)
	Local dx# = tx - cx, dz# = tz - cz
	Local dl# = Sqr(dx * dx + dz * dz)
	If dl < 0.001 Then Return
	Local base# = ATan2(dx / dl, dz / dl)
	Local chosen# = -999.0, i%
	For i = 0 To 18
		Local off# = 0.0
		If i > 0 Then
			Local k% = (i + 1) / 2
			off = 18.0 * k
			If (i Mod 2) = 0 Then off = -off
		End If
		Local a# = base + off
		If N173DirClear(Sin(a), Cos(a), 1.6) Then chosen = a : Exit
	Next
	If chosen = -999.0 Then Return
	N173MoveToward cx + Sin(chosen) * 2.0, cz + Cos(chosen) * 2.0
End Function

; Follow the A* route to (tx,tz); rebuild it when the goal moves or a step
; is blocked. Every step is raycast validated, so walls are never crossed.
Function N173SteerTo(tx#, tz#)
	; Clear line of sight: go STRAIGHT at the target. A* (which can detour
	; around walls) is used only when the target is hidden behind geometry.
	If N173_DirectChase Then
		If N173MoveToward(tx, tz) Then
			N173PathLen = 0 : N173PathIdx = 0 : N173PathGoal = -1
			Return
		End If
	End If

	Local cx# = EntityX(N173_Collider, True), cz# = EntityZ(N173_Collider, True)
	Local gx% = Int(tx - N173_GX0 + 0.5), gz% = Int(tz - N173_GZ0 + 0.5)
	gx = Clamp(gx, 0, N173_GNX - 1) : gz = Clamp(gz, 0, N173_GNZ - 1)
	Local goal% = N173NodeIndex(gx, gz)

	N173PathTimer = N173PathTimer - FpsFactor
	If N173PathLen = 0 Or N173PathIdx >= N173PathLen Or N173PathGoal <> goal Or N173PathTimer <= 0 Then
		N173BuildPath tx, tz
	End If

	If N173PathLen > 0 Then
		Local node% = N173PathNodes(N173PathIdx)
		Local wx# = N173NodeX(node), wz# = N173NodeZ(node)
		If Distance(cx, cz, wx, wz) < 0.35 Then
			N173PathIdx = N173PathIdx + 1
			If N173PathIdx >= N173PathLen Then N173PathLen = 0 : Return
			node = N173PathNodes(N173PathIdx)
			wx = N173NodeX(node) : wz = N173NodeZ(node)
		End If
		If N173MoveToward(wx, wz) Then Return
		; blocked (geometry changed / bad grid node) - recompute once
		N173BuildPath tx, tz
		If N173PathLen > 0 Then
			node = N173PathNodes(0)
			N173MoveToward N173NodeX(node), N173NodeZ(node)
		End If
		Return
	End If
	N173SteerDirect tx, tz
End Function

; =====================================================================
; Death / respawn
; =====================================================================
Function KillPlayer()
	If KillTimer >= 0 Then
		KillTimer = -1.0
		DeathAnim = Rand(0, 1)
		GameState = GS_DEAD
		HintTimer = 0
		PlaySound DamageSnd
		FX_OnKill()
		; the round is NOT reset: 173 retreats to the far corner of the map
		; and waits there until the player respawns
		N173HideInCorner()
	End If
End Function

; After a death SCP-173 hides in a corner of the map instead of the whole
; round being reset. It stays there while the death screen is shown and is
; still there (not teleported back to its spawn) when the player returns.
Function N173HideInCorner()
	If N173_Collider = 0 Then Return
	PositionEntity N173_Collider, N173HomeX, N173HomeY, N173HomeZ, True
	ResetEntity N173_Collider
	RotateEntity N173_Collider, 0, 90, 0, True
	N173_State = 0
	N173_EnemyX = 0 : N173_EnemyZ = 0
	N173_TargetStream = -1 : N173_TargetX = N173HomeX : N173_TargetZ = N173HomeZ
	N173PathLen = 0 : N173PathIdx = 0 : N173PathGoal = -1 : N173PathTimer = 0
	N173ClearPathCache()
	N173_PrevX = N173HomeX : N173_PrevZ = N173HomeZ
	N173_LastDist = 0
	Keep173InBounds()
	If N173_Obj <> 0 Then
		PositionEntity N173_Obj, EntityX(N173_Collider, True), 0.03, EntityZ(N173_Collider, True)
		RotateEntity N173_Obj, 0, EntityYaw(N173_Collider, True) - 180, 0
	End If
End Function

; Respawn the player WITHOUT resetting the round: the breach door stays
; open and SCP-173 stays in the corner it retreated to after the kill.
Function RespawnPlayer()
	PositionEntity Collider, SpawnX, SpawnY, SpawnZ, True
	ResetEntity Collider
	RotateEntity Collider, 0, -90, 0, True   ; face towards the chamber (+X)
	UserPitch = 0 : PlayerSpeed = 0 : DropSpeed = 0

	BlinkTimer = -10.0
	BLINKFREQ = Rnd(490, 700)
	BlurVolume = 0 : CurrZoom = 0
	HeartRate = 0 : HeartVol = 0
	KillTimer = 0 : DeathMSG = ""
	CameraZoom Camera, 1.0

	GameState = GS_PLAY
	HintMSG = "You are awake again. SCP-173 is still out there - do not blink."
	HintTimer = 300

	HidePointer()
	MoveMouse GraphW / 2, GraphH / 2
	FlushKeys() : FlushMouse()
End Function

Function UpdateDeath()
	KillTimer = KillTimer - FpsFactor * 0.8
	BlurVolume = Abs(KillTimer) * 0.12
	CameraZoom Camera, Max(1.0, 1.0 + Abs(KillTimer) / 300.0)
	If KillTimer > -2.5 Then
		; short neck-snap animation before the death screen
		If DeathAnim = 0 Then
			RotateEntity Camera, Curve(-90.0, EntityPitch(Camera), 20.0), EntityYaw(Camera), EntityRoll(Camera)
		Else
			RotateEntity Camera, Curve(90.0, EntityPitch(Camera), 20.0), EntityYaw(Camera), EntityRoll(Camera)
		End If
	End If
End Function

; =====================================================================
; HUD
; =====================================================================
Function DrawHUD()
	Local i%
	SetBuffer BackBuffer()

	; blink meter - copied from DrawGUI() in the original Main.bb,
	; placed bottom-left and scaled to the current resolution
	Local bw% = 204 * MenuScale, bh% = 20 * MenuScale
	Local bx% = 80 * MenuScale, by% = GraphH - 95 * MenuScale
	Color 255, 255, 255
	Rect bx, by, bw, bh, False
	For i = 1 To Int(((204 - 2) * Clamp(BlinkTimer / BLINKFREQ, 0.0, 1.0)) / 10.0)
		DrawImage BlinkMeterIMG, bx + 3 + 10 * (i - 1) * MenuScale, by + 3
	Next
	Color 0, 0, 0
	Rect bx - 50 * MenuScale, by, 30 * MenuScale, 30 * MenuScale
	Color 255, 255, 255
	Rect bx - 51 * MenuScale, by - 1, 32 * MenuScale, 32 * MenuScale, False

	; objective / hint
	If HintTimer > 0 Then
		HintTimer = HintTimer - FpsFactor
		SetFont FntSmall
		Color 220, 220, 220
		Text GraphW / 2, GraphH - 60 * MenuScale, HintMSG, True
		SetFont FntTitle
	End If

	; heartbeat
	If HeartRate > 0 Then
		HeartRate = Max(0, HeartRate - 60 * FpsFactor / 70.0)
		HeartVol = Max(0, HeartVol - 0.02 * FpsFactor)
		Color 255, 40, 40
		Rect GraphW - 60 * MenuScale, 25 * MenuScale, 20 * MenuScale, 20 * MenuScale, True
	End If

	; a soft, steady vignette while being watched (no flicker / stripes)
	If BlurVolume > 0.02 Then
		Color 0, 0, 0
		Rect 0, 0, GraphW, 4 * MenuScale, True
		Rect 0, GraphH - 4 * MenuScale, GraphW, 4 * MenuScale, True
		BlurVolume = Max(0.0, BlurVolume - 0.01 * FpsFactor)
	End If

	; eye lids
	Local d# = BlinkDarkness()
	If d > 0 Then
		Color 0, 0, 0
		Local lid# = (GraphH / 2) * d
		Rect 0, 0, GraphW, lid, True
		Rect 0, GraphH - lid, GraphW, lid, True
	End If

	; death screen
	If GameState = GS_DEAD And KillTimer < -2.5 Then
		Color 0, 0, 0
		Rect 0, 0, GraphW, GraphH, True
		SetFont FntBig
		Color 200, 30, 30
		Text GraphW / 2, GraphH / 2 - 90 * MenuScale, "YOU DIED", True
		SetFont FntSmall
		Color 220, 220, 220
		Text GraphW / 2, GraphH / 2 - 20 * MenuScale, DeathMSG, True, True
		Color 255, 255, 255
		Text GraphW / 2, GraphH / 2 + 90 * MenuScale, "PRESS SPACE TO RESPAWN", True
		SetFont FntTitle
	End If
End Function
