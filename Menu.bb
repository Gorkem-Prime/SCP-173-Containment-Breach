; =====================================================================
; SCP-173 Containment Breach  -  TITLE / MENU / SETTINGS
; =====================================================================

Global MenuBack%, Menu173%, MenuLogo%
Global MenuTab% = 0            ; 0 = main, 1 = settings
Global RebindMode% = 0

Function LoadMenuAssets()
	MenuBack = LoadImage("GFX\menu\back.jpg")
	Menu173  = LoadImage("GFX\menu\173back.jpg")
	MenuLogo = LoadImage("GFX\menu\scptext.jpg")
	ResizeImage MenuBack, GraphW, GraphH
	ResizeImage Menu173, 420 * MenuScale, 360 * MenuScale
End Function

; ---------------------------------------------------------------------
; Opening screen - the credit line requested by the project
; ---------------------------------------------------------------------
Function ShowSplash()
	Local t% = MilliSecs()
	Repeat
		SetBuffer BackBuffer()
		Color 0, 0, 0 : Rect 0, 0, GraphW, GraphH, True

		SetFont FntBig
		Color 190, 25, 25
		Text GraphW / 2, GraphH / 2 - 90 * MenuScale, "SCP-173", True

		SetFont FntTitle
		Color 235, 235, 235
		Text GraphW / 2, GraphH / 2 - 10 * MenuScale, "CONTAINMENT BREACH", True

		Color 150, 150, 155
		Text GraphW / 2, GraphH / 2 + 55 * MenuScale, "by GorkemPrime", True

		Color 90, 90, 90
		SetFont FntSmall
		Text GraphW / 2, GraphH - 60 * MenuScale, "press any key to continue", True

		Flip
		If GetKey() <> 0 Then Exit
	Until MilliSecs() - t > 3000
	FlushKeys()
End Function

; ---------------------------------------------------------------------
; Generic button
; ---------------------------------------------------------------------
Function DrawBtn(x#, y#, w#, h#, label$)
	Local mx% = MouseX(), my% = MouseY()
	Local hover% = (mx >= x And mx <= x + w And my >= y And my <= y + h)

	If hover Then
		Color 85, 20, 20 : Rect x, y, w, h, True
		Color 220, 90, 90
	Else
		Color 22, 22, 25 : Rect x, y, w, h, True
		Color 110, 110, 115
	End If
	Rect x, y, w, h, False

	SetFont FntSmall
	If hover Then Color 255, 235, 235 Else Color 215, 215, 215
	Text x + w / 2, y + h / 2, label$, True, True

	Local clicked% = (hover And MouseHit(1))
	If clicked Then PlaySound ButtonSnd
	Return clicked
End Function

Function DrawCursor()
	If Not VirtMouseReady Then Return
	Color 255, 255, 255
	Line MouseX() - 9, MouseY(), MouseX() + 9, MouseY()
	Line MouseX(), MouseY() - 9, MouseX(), MouseY() + 9
End Function

; ---------------------------------------------------------------------
; Pause screen (Escape)
; ---------------------------------------------------------------------
Global PauseReturn% = GS_PLAY

Function ResumeGame()
	GameState = PauseReturn
	HidePointer()
	MoveMouse GraphW / 2, GraphH / 2
	FlushKeys() : FlushMouse()
End Function

Function DrawPauseMenu()
	SetBuffer BackBuffer()
	Color 0, 0, 0 : Rect 0, 0, GraphW, GraphH, True

	SetFont FntBig
	Color 200, 35, 35
	Text GraphW / 2, 150 * MenuScale, "PAUSED", True
	SetFont FntSmall
	Color 150, 150, 155
	Text GraphW / 2, 205 * MenuScale, "SCP-173 is waiting.", True

	Local bx# = GraphW / 2 - 150 * MenuScale
	Local by# = 290 * MenuScale
	Local bw# = 300 * MenuScale
	Local bh# = 62 * MenuScale
	Local gap# = 78 * MenuScale

	If DrawBtn(bx, by, bw, bh, "RESUME") Or KeyHit(2) Then ResumeGame()
	If DrawBtn(bx, by + gap, bw, bh, "RESTART ROUND") Or KeyHit(3) Then ResetRound()
	; ESC / MAIN MENU keeps the LAN room alive - use LEAVE LAN to quit it
	If DrawBtn(bx, by + gap * 2, bw, bh, "MAIN MENU") Or KeyHit(4) Then
		DestroyWorld()
		GameState = GS_MENU
		MenuTab = 0
		EnterMouseMode()
	End If
	If NetMode <> NET_OFFLINE Then
		If DrawBtn(bx, by + gap * 3, bw, bh, "LEAVE LAN") Or KeyHit(6) Then
			NetLeaveLAN()
			DestroyWorld()
			GameState = GS_MENU
			MenuTab = 0
			EnterMouseMode()
		End If
	End If
	If DrawBtn(bx, by + gap * 4, bw, bh, "QUIT") Or KeyHit(5) Then End

	SetFont FntSmall
	Color 120, 120, 125
	Text GraphW / 2, GraphH - 45 * MenuScale, "1 = resume   2 = restart   3 = main menu   4 = leave LAN   5 = quit", True
	DrawCursor()
End Function

; ---------------------------------------------------------------------
; TCP multiplayer room menu
; ---------------------------------------------------------------------
Function UpdateMultiplayerMenu()
	If NetRoomList Then
		UpdateRoomListMenu()
		Return
	End If

	SetFont FntBig
	Color 210, 210, 215
	Text GraphW / 2, 85 * MenuScale, "MULTIPLAYER", True
	SetFont FntSmall

	Local bx# = GraphW / 2 - 170 * MenuScale
	Local by# = 180 * MenuScale
	Local bw# = 340 * MenuScale
	Local bh# = 58 * MenuScale
	Local gap# = 72 * MenuScale

	If DrawBtn(bx, by, bw, bh, "CREATE ROOM") Or KeyHit(2) Then
		If NetStartHost() Then
			BuildWorld()
			FlushKeys() : FlushMouse()
		End If
	End If

	If DrawBtn(bx, by + gap, bw, bh, "JOIN ROOM") Or KeyHit(3) Then
		; no IP typing - open the discovered room list
		NetRoomList = True
		NetDiscStartBrowse()
		FlushMouse()
	End If

	If DrawBtn(bx, by + gap * 2, bw, bh, "BACK") Or KeyHit(4) Then MenuTab = 0

	If NetMode <> NET_OFFLINE Then
		If DrawBtn(bx, by + gap * 3, bw, bh, "LEAVE LAN") Or KeyHit(6) Then
			NetLeaveLAN()
			NetMsg = "You left the LAN room."
			NetMsgTimer = 300
		End If
	End If

	Color 150, 150, 155
	Text GraphW / 2, by + gap * 4, "Rooms on your local network are found automatically.", True
	Color 120, 200, 255
	Text GraphW / 2, by + gap * 4 + 30 * MenuScale, NetMsg, True
	DrawCursor()
End Function

; ---------------------------------------------------------------------
; "JOIN ROOM" - list of rooms found on the LAN (no IP input)
; ---------------------------------------------------------------------
Function UpdateRoomListMenu()
	SetFont FntBig
	Color 210, 210, 215
	Text GraphW / 2, 85 * MenuScale, "JOIN ROOM", True
	SetFont FntSmall
	Color 150, 150, 155
	Text GraphW / 2, 132 * MenuScale, "Rooms found on your network:", True

	; drive the UDP discovery while this screen is open
	NetDiscPoll()

	Local bx# = GraphW / 2 - 300 * MenuScale
	Local by# = 180 * MenuScale
	Local bw# = 600 * MenuScale
	Local bh# = 52 * MenuScale
	Local gap# = 60 * MenuScale

	Local n% = 0
	Local rm.NetRoom
	For rm = Each NetRoom
		If n < 6 Then
			If DrawBtn(bx, by + gap * n, bw, bh, rm\name + "   " + rm\ip + "   [" + rm\players + "/" + NET_MAX_PLAYERS + "]") Then
				NetRoomList = False
				NetDiscStop()
				Local ip$ = rm\ip
				If NetJoinRoom(ip) Then
					NetClearRooms()
					BuildWorld()
					FlushKeys() : FlushMouse()
				Else
					; joining failed - go back to the list and keep searching
					NetRoomList = True
					NetDiscStartBrowse()
				End If
				Return
			End If
			n = n + 1
		End If
	Next

	If n = 0 Then
		Color 150, 150, 155
		Text GraphW / 2, by + 20 * MenuScale, "Searching... (the host must click CREATE ROOM first)", True
	End If

	Local ly# = by + gap * 6 + 8 * MenuScale
	If DrawBtn(GraphW / 2 - 220 * MenuScale, ly, 200 * MenuScale, 50 * MenuScale, "REFRESH") Then
		NetDiscBuildScanList()
	End If
	If DrawBtn(GraphW / 2 + 20 * MenuScale, ly, 200 * MenuScale, 50 * MenuScale, "BACK") Or KeyHit(4) Then
		NetRoomList = False
		NetDiscStop()
	End If

	Color 120, 120, 125
	Text GraphW / 2, GraphH - 45 * MenuScale, "Click a room to join. ESC = back.", True
	DrawCursor()
	SetFont FntTitle
End Function

Function RowLabel(label$, value$, x#, y#, w#)
	Color 190, 190, 195
	Text x, y, label$
	Color 230, 90, 90
	Text x + w, y, value$, True
End Function

; ---------------------------------------------------------------------
; Main menu
; ---------------------------------------------------------------------
Function UpdateMainMenu()
	SetBuffer BackBuffer()

	Color 0, 0, 0 : Rect 0, 0, GraphW, GraphH, True
	; scale the original menu artwork to the current window size
	If MenuBack <> 0 Then DrawImage MenuBack, 0, 0
	; the original blinking statue image on the right
	If Menu173 <> 0 Then
		If (MilliSecs() / 700) Mod 5 <> 0 Then
			DrawImage Menu173, GraphW - ImageWidth(Menu173), GraphH - ImageHeight(Menu173)
		End If
	End If
	Color 0, 0, 0
	Rect 0, 0, GraphW, GraphH * 0.62, True

	SetFont FntBig
	Color 195, 30, 30
	Text GraphW / 2, 95 * MenuScale, "SCP-173", True
	SetFont FntTitle
	Color 235, 235, 235
	Text GraphW / 2, 155 * MenuScale, "CONTAINMENT BREACH", True
	Color 160, 160, 165
	Text GraphW / 2, 200 * MenuScale, "by GorkemPrime", True

	If MenuTab = 0 Then
		Local bx# = GraphW / 2 - 150 * MenuScale
		Local by# = 290 * MenuScale
		Local bw# = 300 * MenuScale
		Local bh# = 62 * MenuScale
		Local gap# = 78 * MenuScale

		If DrawBtn(bx, by, bw, bh, "PLAY") Or KeyHit(2) Then
			If NetMode <> NET_OFFLINE Then NetLeaveLAN()
			BuildWorld()
			FlushKeys() : FlushMouse()
		End If
		If DrawBtn(bx, by + gap, bw, bh, "SETTINGS") Or KeyHit(3) Then MenuTab = 1
		If DrawBtn(bx, by + gap * 2, bw, bh, "MULTIPLAYER") Or KeyHit(4) Then MenuTab = 2
		If DrawBtn(bx, by + gap * 3, bw, bh, "QUIT") Or KeyHit(5) Then End

		SetFont FntSmall
		Color 120, 120, 125
		Text GraphW / 2, GraphH - 45 * MenuScale, "1 = play   2 = settings   3 = multiplayer   4 = quit", True
	ElseIf MenuTab = 2 Then
		UpdateMultiplayerMenu()
	Else
		UpdateSettingsMenu()
	End If

	DrawCursor()
	SetFont FntTitle
End Function

; ---------------------------------------------------------------------
; Settings
; ---------------------------------------------------------------------
Function CycleResolution()
	Local cur% = 0, nw%, nh%
	Select GraphW
		Case 800  : cur = 1
		Case 1024 : cur = 2
		Case 1280 : cur = 3
		Case 1366 : cur = 4
		Case 1600 : cur = 5
		Case 1920 : cur = 6
	End Select
	cur = cur + 1
	If cur > 6 Then cur = 1
	Select cur
		Case 1 : nw = 800  : nh = 600
		Case 2 : nw = 1024 : nh = 768
		Case 3 : nw = 1280 : nh = 720
		Case 4 : nw = 1366 : nh = 768
		Case 5 : nw = 1600 : nh = 900
		Case 6 : nw = 1920 : nh = 1080
	End Select
	GraphW = nw : GraphH = nh
	SaveSettings()
End Function

Function UpdateSettingsMenu()
	Local x# = GraphW / 2 - 300 * MenuScale
	Local y# = 130 * MenuScale
	Local w# = 600 * MenuScale
	Local h# = 55 * MenuScale
	Local gap# = 66 * MenuScale
	Local rx# = x + w - 200 * MenuScale

	SetFont FntBig
	Color 210, 210, 215
	Text GraphW / 2, 85 * MenuScale, "SETTINGS", True
	SetFont FntSmall

	; fullscreen / resolution (applied after restarting the game)
	If DrawBtn(rx, y, 200 * MenuScale, h, "FULLSCREEN: " + Str(FullScreen)) Then
		FullScreen = 1 - FullScreen
		SaveSettings()
	End If
	RowLabel "Fullscreen (restart to apply)", "", x, y + h / 2, 0
	y = y + gap

	If DrawBtn(rx, y, 200 * MenuScale, h, Str(GraphW) + " x " + Str(GraphH)) Or KeyHit(2) Then CycleResolution()
	RowLabel "Resolution (restart to apply)", "", x, y + h / 2, 0
	y = y + gap

	; mouse sensitivity
	If DrawBtn(rx, y, 95 * MenuScale, h, "<") Then
		MouseSens = Clamp(MouseSens - 0.1, -0.5, 2.0)
		SaveSettings()
	End If
	If DrawBtn(rx + 105 * MenuScale, y, 95 * MenuScale, h, ">") Then
		MouseSens = Clamp(MouseSens + 0.1, -0.5, 2.0)
		SaveSettings()
	End If
	RowLabel "Mouse sensitivity", Str(MouseSens), x, y + h / 2, w - 240 * MenuScale
	y = y + gap

	; volume
	If DrawBtn(rx, y, 95 * MenuScale, h, "<") Then
		SFXVolume = Clamp(SFXVolume - 0.1, 0.0, 1.0)
		SaveSettings()
	End If
	If DrawBtn(rx + 105 * MenuScale, y, 95 * MenuScale, h, ">") Then
		SFXVolume = Clamp(SFXVolume + 0.1, 0.0, 1.0)
		SaveSettings()
	End If
	RowLabel "Sound volume", Str(SFXVolume), x, y + h / 2, w - 240 * MenuScale
	y = y + gap

	If DrawBtn(rx, y, 200 * MenuScale, h, "INVERT Y: " + Str(InvertMouse)) Then
		InvertMouse = 1 - InvertMouse
		SaveSettings()
	End If
	RowLabel "Invert mouse Y", "", x, y + h / 2, 0
	y = y + gap

	If RebindMode Then
		If DrawBtn(rx, y, 200 * MenuScale, h, "PRESS A KEY...") Then RebindMode = False
		Local k% = GetKey()
		If k <> 0 And k <> 1 Then
			BlinkKey = k
			RebindMode = False
			SaveSettings()
			FlushKeys()
		End If
	Else
		If DrawBtn(rx, y, 200 * MenuScale, h, "BLINK KEY: " + KeyName(BlinkKey)) Then RebindMode = True
	End If
	RowLabel "Blink key (hold to keep eyes shut)", "", x, y + h / 2, 0
	y = y + gap

	If DrawBtn(GraphW / 2 - 150 * MenuScale, y + 20 * MenuScale, 300 * MenuScale, 60 * MenuScale, "BACK") Or KeyHit(9) Then MenuTab = 0

	; keyboard alternatives for every setting
	If KeyHit(1) Then FullScreen = 1 - FullScreen : SaveSettings()
	If KeyHit(3) Then
		MouseSens = Clamp(MouseSens - 0.1, -0.5, 2.0) : SaveSettings()
	End If
	If KeyHit(4) Then
		MouseSens = Clamp(MouseSens + 0.1, -0.5, 2.0) : SaveSettings()
	End If
	If KeyHit(5) Then
		SFXVolume = Clamp(SFXVolume - 0.1, 0.0, 1.0) : SaveSettings()
	End If
	If KeyHit(6) Then
		SFXVolume = Clamp(SFXVolume + 0.1, 0.0, 1.0) : SaveSettings()
	End If
	If KeyHit(7) Then InvertMouse = 1 - InvertMouse : SaveSettings()
	If KeyHit(8) Then RebindMode = True

	SetFont FntSmall
	Color 120, 120, 125
	Text GraphW / 2, GraphH - 35 * MenuScale, "1=fullscreen 2=resolution 3/4=sensitivity 5/6=volume 7=invert 8=blink key 9=back", True
	DrawCursor()
End Function

; simple key name helper
Function KeyName$(k%)
	Select k
		Case 57 : Return "SPACE"
		Case 29 : Return "CTRL"
		Case 42 : Return "SHIFT"
		Case 15 : Return "TAB"
		Case 1  : Return "ESC"
		Case 28 : Return "ENTER"
	End Select
	Return "KEY " + Str(k)
End Function
