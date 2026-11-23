; =====================================================================
; SCP-173 CONTAINMENT BREACH  -  by GorkemPrime
; ---------------------------------------------------------------------
; A standalone mini game built ONLY from the SCP-173 parts of the
; original "SCP - Containment Breach" source (Blitz3D 1.108).
;
; Included from the original game:
;   - SCP-173 model + textures (GFX\npcs\173_2.b3d, GFx\173box.b3d ...)
;   - SCP-173 blink/observation AI and kill logic (NPCs.bb)
;   - the first spawn map (GFX\map\173bright_opt.rmesh = cells + chamber)
;   - SCP-173 sounds
; Everything else (other SCPs, events, items, save system) is omitted.
; =====================================================================

Include "Core.bb"
Include "SCP173.bb"
Include "Menu.bb"
Include "Multiplayer.bb"
Include "FX.bb"
Include "P90.bb"

; ---------------------------------------------------------------------
; Settings
; ---------------------------------------------------------------------
GraphW      = INI_Int(OptionFile, "options", "width", 1280)
GraphH      = INI_Int(OptionFile, "options", "height", 720)
FullScreen  = INI_Int(OptionFile, "options", "fullscreen", 0)
MouseSens   = INI_Float(OptionFile, "options", "mouse sensitivity", 0.0)
InvertMouse = INI_Int(OptionFile, "options", "invert mouse y", 0)
SFXVolume   = INI_Float(OptionFile, "audio", "sound volume", 1.0)
ShowFPS     = INI_Int(OptionFile, "options", "show FPS", 0)
BlinkKey    = INI_Int(OptionFile, "binds", "Blink key", 57)

If FullScreen Then
	GraphW = 1920 : GraphH = 1080
Else
	If GraphW < 640 Then GraphW = 1280
	If GraphH < 480 Then GraphH = 720
End If

If FullScreen Then
	Graphics3D GraphW, GraphH, 0, 1
Else
	Graphics3D GraphW, GraphH, 0, 2
End If

SetBuffer BackBuffer()
AntiAlias 1                       ; smoother edges (like the original options)
AppTitle "SCP-173 Containment Breach by GorkemPrime"
MenuScale = GraphH / 1024.0
If MenuScale < 0.7 Then MenuScale = 0.7

FntTitle = LoadFont("GFX\courbd.ttf", Int(28 * MenuScale))
FntBig   = LoadFont("GFX\courbd.ttf", Int(64 * MenuScale))
FntSmall = LoadFont("GFX\cour.ttf",  Int(19 * MenuScale))
SetFont FntTitle

LoadAssets()
LoadMenuAssets()

; Show the required credit line at game open
ShowSplash()
EnterMouseMode()

FlushKeys()
FlushMouse()
LastTime = MilliSecs()

; ---------------------------------------------------------------------
; Helpers
; ---------------------------------------------------------------------
Function EnterMouseMode()
	ShowPointer()
	VirtMouseX = GraphW / 2
	VirtMouseY = GraphH / 2
	VirtMouseReady = True
End Function

; Switching between windowed and fullscreen has to create a brand new
; Direct3D device. Everything that lives in video memory (textures of the
; map, 173, the P90, every image and font) is destroyed by that switch, so
; the old world is torn down first and rebuilt afterwards. Doing the
; Graphics3D call on top of a live world is what caused the occasional
; "memory access violation".
Function ToggleFullscreen()
	Local oldState% = GameState
	Local hasWorld% = (Camera <> 0)

	; ---- remember the round so the rebuild continues where it was ----
	Local px#, py#, pz#, pyaw#, ppitch#, pspeed#, pdrop#
	Local nx#, nz#, nyaw#, nactive%, nstate#, nprevx#, nprevz#
	Local doorOpen%, doorState#
	Local blinkT#, blinkFreq#
	Local p90state%, p90ammo%, p90res%
	Local hint$ = HintMSG, hintT# = HintTimer
	Local killT# = KillTimer, death$ = DeathMSG
	Local netMode% = NetMode, netIP$ = NetIP

	If hasWorld Then
		px = EntityX(Collider, True) : py = EntityY(Collider, True) : pz = EntityZ(Collider, True)
		pyaw = EntityYaw(Collider, True)
		ppitch = UserPitch : pspeed = PlayerSpeed : pdrop = DropSpeed
		nx = EntityX(N173_Collider, True) : nz = EntityZ(N173_Collider, True)
		nyaw = EntityYaw(N173_Collider, True)
		nactive = N173_Active : nstate = N173_State
		nprevx = N173_PrevX : nprevz = N173_PrevZ
		doorOpen = DoorOpened : doorState = DoorOpenState
		blinkT = BlinkTimer : blinkFreq = BLINKFREQ
		p90state = P90_State : p90ammo = P90_Ammo : p90res = P90_Reserve
	End If

	; ---- destroy every video-memory resource BEFORE the mode switch ----
	If hasWorld Then DestroyWorld()
	If MenuBack <> 0 Then FreeImage MenuBack
	If Menu173 <> 0 Then FreeImage Menu173
	If MenuLogo <> 0 Then FreeImage MenuLogo
	If BlinkMeterIMG <> 0 Then FreeImage BlinkMeterIMG
	MenuBack = 0 : Menu173 = 0 : MenuLogo = 0 : BlinkMeterIMG = 0

	FullScreen = 1 - FullScreen
	If FullScreen Then
		; fullscreen is always 1920 x 1080
		GraphW = 1920 : GraphH = 1080
		Graphics3D GraphW, GraphH, 0, 1
	Else
		GraphW = 1280 : GraphH = 720
		Graphics3D GraphW, GraphH, 0, 2
	End If
	SetBuffer BackBuffer()
	AntiAlias 1
	MenuScale = GraphH / 1024.0
	If MenuScale < 0.7 Then MenuScale = 0.7
	FntTitle = LoadFont("GFX\courbd.ttf", Int(28 * MenuScale))
	FntBig   = LoadFont("GFX\courbd.ttf", Int(64 * MenuScale))
	FntSmall = LoadFont("GFX\cour.ttf",  Int(19 * MenuScale))
	SetFont FntTitle

	; reload the 2D images from the original files
	LoadMenuAssets()
	BlinkMeterIMG = LoadImage("GFX\BlinkMeter.jpg")
	ResizeImage BlinkMeterIMG, 10 * MenuScale, 14 * MenuScale

	If hasWorld Then
		; rebuild the map, its textures, the P90 and the FX on the new device
		BuildWorld()

		; ---- restore the round ----
		PositionEntity Collider, px, py, pz, True
		ResetEntity Collider
		RotateEntity Collider, 0, pyaw, 0, True
		UserPitch = ppitch : PlayerSpeed = pspeed : DropSpeed = pdrop

		PositionEntity N173_Collider, nx, 0.35, nz, True
		ResetEntity N173_Collider
		RotateEntity N173_Collider, 0, nyaw, 0, True
		N173_Active = nactive : N173_State = nstate
		N173_PrevX = nprevx : N173_PrevZ = nprevz

		DoorOpened = doorOpen : DoorOpenState = doorState
		If doorOpen Then
			PositionEntity DoorLeft,  288 * RoomScale, 0, 384 * RoomScale - 2.2 * doorState
			PositionEntity DoorRight, 288 * RoomScale, 0, 384 * RoomScale + 2.2 * doorState
			ResetEntity DoorLeft : ResetEntity DoorRight
		End If

		BlinkTimer = blinkT : BLINKFREQ = blinkFreq
		P90_Ammo = p90ammo : P90_Reserve = p90res
		If P90_Obj <> 0 And (p90state = 1 Or p90state = 2) Then
			P90_State = 2 : P90_Anim = 1.0
			ShowEntity P90_Obj
		End If
		HintMSG = hint : HintTimer = hintT
		KillTimer = killT : DeathMSG = death
		GameState = oldState

		; bring the LAN session back if there was one
		If netMode = NET_HOST Then
			NetStartHost()
		ElseIf netMode = NET_CLIENT Then
			NetJoinRoom(netIP)
		End If
	End If

	If GameState = GS_PLAY Or GameState = GS_DEAD Then
		HidePointer()
		MoveMouse GraphW / 2, GraphH / 2
	Else
		EnterMouseMode()
	End If
	SaveSettings()
End Function

Function DestroyWorld()
	NetDisconnect()
	If N173_SoundChn <> 0 Then
		StopChannel N173_SoundChn
		N173_SoundChn = 0
	End If
	; bullets / impact sparks own entities that ClearWorld is about to free
	P90_Reset()
	ClearWorld()
	; LoadRoomMesh caches texture handles - ClearWorld just freed them, so
	; the cache must be emptied or a rebuilt map would use dead textures
	ClearMaterialCache()
	Door02Tex = 0
	Camera = 0
	Collider = 0
	RoomObj = 0
	N173_Collider = 0
	N173_Obj = 0
	N173_Box = 0
	DoorPivot = 0
	DoorLeft = 0
	DoorRight = 0
	P90_Obj = 0
	P90_Light = 0
	P90_Held = 0
End Function

; ---------------------------------------------------------------------
; Main loop
; ---------------------------------------------------------------------
Repeat
	; F11 toggles fullscreen at any time
	If KeyHit(87) Then ToggleFullscreen()

	; track the menu cursor ourselves (independent of pointer capture)
	If GameState = GS_MENU Or GameState = GS_PAUSE Then
		If VirtMouseReady Then
			VirtMouseX = Clamp(VirtMouseX + MouseXSpeed(), 0, GraphW)
			VirtMouseY = Clamp(VirtMouseY + MouseYSpeed(), 0, GraphH)
		End If
	End If

	; frame timing (70 FPS baseline, like the original FPSfactor)
	CurTime% = MilliSecs()
	FpsFactor = Clamp((CurTime - LastTime) * 70.0 / 1000.0, 0.2, 3.0)
	LastTime = CurTime
	GameTime = GameTime + FpsFactor / 70.0

	If GameState = GS_MENU Then
		If (MenuTab = 1 Or MenuTab = 2) And KeyHit(1) Then
			If NetRoomList Then
				; ESC inside the room list -> back to the multiplayer menu
				NetRoomList = False
				NetDiscStop()
			Else
				MenuTab = 0
			End If
		End If
		UpdateMainMenu()
	Else
		; Escape -> pause / resume
		If KeyHit(1) Then
			If GameState = GS_PAUSE Then
				ResumeGame()
			Else
				PauseReturn = GameState
				GameState = GS_PAUSE
				EnterMouseMode()
			End If
		End If

		If GameState = GS_PAUSE Then
			DrawPauseMenu()
		Else
			If GameState = GS_PLAY Then
				UpdateBlink()
				UpdatePlayer()
				UpdateCellDoors()
				UpdateDoor()
				If NetMode <> NET_CLIENT Then Update173()
				NetUpdate()
			Else
				UpdateDeath()
				; respawn WITHOUT resetting the round (173 stays in its
				; corner, the breach door stays open)
				If KeyHit(57) Or KeyHit(28) Then RespawnPlayer()
			End If

			; camera "scare zoom" decay + all visual FX state
			CurrZoom = Max(0.0, CurrZoom - FpsFactor * 1.2)
			P90_Update()
			FX_Update()
			FX_ApplyCamera()

			; collision detection / entity updates (required for the floor!)
			UpdateWorld()
			LockHeights()

			RenderWorld
			FX_DrawScreen()
			DrawHUD()
			P90_DrawHUD()
			NetDrawStatus()
		End If
	End If

	If ShowFPS Then
		SetFont FntSmall
		Color 120, 120, 120
		Text GraphW - 20, 12, Int(70.0 / FpsFactor) + " FPS", True
		SetFont FntTitle
	End If

	Flip
Forever
