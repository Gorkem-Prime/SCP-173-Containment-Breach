; =====================================================================
; LAN Multiplayer - host a room, or pick one from an automatic room list
; ---------------------------------------------------------------------
; Uses Blitz3D's raw TCP streams for the game data, and a small UDP
; discovery protocol so "JOIN ROOM" can list the rooms on the network:
;
;   host   : listens on NET_DISC_PORT and answers room queries
;   client : needs no IP - it scans the local subnet with unicast UDP
;            queries and lists every room that answers
;
; The host runs SCP-173's AI and sends its position to clients. Other
; players are drawn as Class-D subject figures (suit, head, limbs + a
; floating subject tag), not cubes. The host also collects every player's
; "am I watching 173?" state: the statue only moves when NOBODY watches it.
; =====================================================================

Const NET_PORT% = 5555
Const NET_DISC_PORT% = 5557
Const NET_MAX_PLAYERS% = 8

Const NET_OFFLINE% = 0, NET_HOST% = 1, NET_CLIENT% = 2

Global NetMode% = NET_OFFLINE
Global NetServer% = 0
Global NetStream% = 0
Global NetIP$ = "127.0.0.1"
Global NetTick% = 0
Global NetMsg$ = ""
Global NetMsgTimer# = 0.0
Global NetAvatarMesh% = 0

; LAN connection outlives a round / a trip to the main menu. It is only
; closed by an explicit "LEAVE LAN" (or when the host sends BYE).
Global NetPreserve% = False
Global NetKicked% = False
Global NetKickMsg$ = ""
Global NetLastHostMsg% = 0
; True when at least one REMOTE player is currently watching SCP-173
Global NetRemoteWatching% = False
; on a client this is the avatar that represents the host
Global NetHostAvatar.RemotePlayer

; room shown in the discovery replies
Global NetRoomName$ = "SCP-173 ROOM"

; UDP discovery state: 0 = off, 1 = host listener, 2 = room browser
Global NetUdp% = 0
Global NetDiscMode% = 0
Global NetRoomList% = False      ; the "JOIN ROOM" screen is open

; subnet scan (broadcast is not available in Blitz3D 1.108 - sending to
; 255.255.255.255 fails, so we probe the local /24 with unicast instead)
Global NetScanIPs% = 0
Dim NetScanBase%(15)
Global NetScanIdx% = 0
Global NetScanT# = 0.0

Function SplitPart$(s$, idx%)
	Local cur% = 1, res$ = "", ch$, i%
	For i = 1 To Len(s$)
		ch = Mid(s$, i, 1)
		If ch = "|" Then
			If cur = idx Then Return res
			cur = cur + 1 : res = ""
		Else
			res = res + ch
		End If
	Next
	If cur = idx Then Return res
	Return ""
End Function

; ---------------------------------------------------------------------
; avatars
; ---------------------------------------------------------------------
; A remote player is a Class-D subject: orange suit, pale head, dark visor,
; arms and legs. All parts are non-pickable so 173's raycasts ignore them.
Function NetBuildAvatarTemplate()
	If NetAvatarMesh <> 0 Then Return

	Local root% = CreatePivot()
	Local part%
	part = CreateCube() : EntityParent part, root
	ScaleEntity part, 0.34, 0.56, 0.20 : PositionEntity part, 0, 1.05, 0
	EntityColor part, 205, 120, 35 : EntityPickMode part, 0

	part = CreateSphere() : EntityParent part, root
	ScaleEntity part, 0.17, 0.19, 0.17 : PositionEntity part, 0, 1.56, 0
	EntityColor part, 195, 175, 150 : EntityPickMode part, 0

	part = CreateCube() : EntityParent part, root
	ScaleEntity part, 0.23, 0.07, 0.05 : PositionEntity part, 0, 1.59, 0.15
	EntityColor part, 25, 25, 30 : EntityPickMode part, 0

	part = CreateCube() : EntityParent part, root
	ScaleEntity part, 0.10, 0.48, 0.12 : PositionEntity part, -0.26, 1.06, 0
	EntityColor part, 190, 110, 30 : EntityPickMode part, 0

	part = CreateCube() : EntityParent part, root
	ScaleEntity part, 0.10, 0.48, 0.12 : PositionEntity part, 0.26, 1.06, 0
	EntityColor part, 190, 110, 30 : EntityPickMode part, 0

	part = CreateCube() : EntityParent part, root
	ScaleEntity part, 0.13, 0.60, 0.14 : PositionEntity part, -0.11, 0.30, 0
	EntityColor part, 75, 78, 88 : EntityPickMode part, 0

	part = CreateCube() : EntityParent part, root
	ScaleEntity part, 0.13, 0.60, 0.14 : PositionEntity part, 0.11, 0.30, 0
	EntityColor part, 75, 78, 88 : EntityPickMode part, 0

	HideEntity root
	NetAvatarMesh = root
End Function

Function NetSpawnAvatar%()
	If NetAvatarMesh = 0 Then NetBuildAvatarTemplate()
	Local o% = CopyEntity(NetAvatarMesh)
	ShowEntity o
	Return o
End Function

Function NetMakeAvatar.RemotePlayer(stream%)
	Local rp.RemotePlayer = New RemotePlayer
	rp\stream = stream
	rp\obj = NetSpawnAvatar()
	rp\tag = "D-" + stream
	rp\lastSeen = MilliSecs()
	Return rp
End Function

; ClearWorld() frees every entity, including the template and all copies.
Function NetClearAvatarRefs()
	Local rp.RemotePlayer
	For rp = Each RemotePlayer
		rp\obj = 0
	Next
	NetAvatarMesh = 0
End Function

; Rebuild the figures after a world rebuild (new round / fullscreen switch).
Function NetRebuildAvatars()
	Local rp.RemotePlayer
	For rp = Each RemotePlayer
		If rp\obj <> 0 Then FreeEntity rp\obj
		rp\obj = 0
		If rp\stream <> NetStream Or NetMode = NET_HOST Then
			rp\obj = NetSpawnAvatar()
			PositionEntity rp\obj, rp\x, rp\y + 0.9, rp\z
			RotateEntity rp\obj, 0, rp\yaw, 0
		End If
	Next
End Function

; ---------------------------------------------------------------------
; Host / join
; ---------------------------------------------------------------------
Function NetStartHost%()
	If NetMode = NET_HOST Then Return True
	If NetMode = NET_CLIENT Then NetDisconnect()
	NetServer = CreateTCPServer(NET_PORT)
	If NetServer = 0 Then
		NetMsg = "Could not open port " + NET_PORT
		NetMsgTimer = 300
		Return False
	End If
	NetMode = NET_HOST
	NetPreserve = True
	NetKicked = False
	NetMsg = "Room created. Waiting for players on port " + NET_PORT
	NetMsgTimer = 400
	NetDiscStartHost()
	Return True
End Function

Function NetJoinRoom%(ip$)
	If NetMode <> NET_OFFLINE Then NetDisconnect()
	If ip$ = "" Then ip$ = "127.0.0.1"
	NetStream = OpenTCPStream(ip$, NET_PORT)
	If NetStream = 0 Then
		NetMsg = "Could not connect to " + ip$
		NetMsgTimer = 300
		Return False
	End If
	NetIP = ip$ : NetMode = NET_CLIENT
	NetPreserve = True : NetKicked = False
	NetLastHostMsg = MilliSecs()
	; the client needs an entry to read the host's stream, but must NOT
	; draw itself as a third-person figure
	Local me.RemotePlayer = New RemotePlayer
	me\stream = NetStream : me\obj = 0 : me\tag = "YOU"
	me\lastSeen = MilliSecs()
	NetHostAvatar = New RemotePlayer
	NetHostAvatar\stream = 0 : NetHostAvatar\tag = "HOST"
	NetMsg = "Connected to " + ip$
	NetMsgTimer = 300
	Return True
End Function

Function NetDisconnect()
	Local rp.RemotePlayer
	; tell the other players that the host is gone before closing the pipes
	If NetMode = NET_HOST Then
		For rp = Each RemotePlayer
			If rp\stream <> 0 Then WriteLine rp\stream, "BYE"
		Next
	End If
	If NetServer <> 0 Then CloseTCPServer NetServer
	If NetStream <> 0 Then CloseTCPStream NetStream
	For rp = Each RemotePlayer
		If rp\stream <> 0 And rp\stream <> NetStream Then CloseTCPStream rp\stream
		If rp\obj <> 0 Then FreeEntity rp\obj
		Delete rp
	Next
	If NetAvatarMesh <> 0 Then FreeEntity NetAvatarMesh
	NetAvatarMesh = 0
	NetServer = 0 : NetStream = 0
	NetMode = NET_OFFLINE
	NetPreserve = False : NetKicked = False
	NetRemoteWatching = False
	NetHostAvatar = Null
	NetDiscStop()
End Function

; Explicitly leave the LAN (used by the menu button / PLAY / CREATE / JOIN).
Function NetLeaveLAN()
	If NetMode = NET_OFFLINE Then Return
	NetDisconnect()
End Function

; Called while in the main menu so a paused / menuing host still answers
; clients and keeps the session alive.
Function NetIdle()
	If NetMode = NET_OFFLINE Then Return
	NetDiscPoll()
	NetTick = NetTick + 1
	If NetMode = NET_HOST Then
		Local s% = AcceptTCPStream(NetServer)
		If s <> 0 Then NetMakeAvatar(s)
	End If
	Local rp.RemotePlayer
	For rp = Each RemotePlayer
		If rp\stream <> 0 And ReadAvail(rp\stream) > 0 Then
			Local l$ = ReadLine$(rp\stream)
			rp\lastSeen = MilliSecs()
			If NetMode = NET_CLIENT Then NetLastHostMsg = MilliSecs()
			If l <> "" And l <> "HB" Then NetApplyLine l$, rp
		End If
		If NetMode = NET_HOST And NetTick Mod 10 = 0 Then WriteLine rp\stream, "HB"
	Next
	NetCheckHostGone()
End Function

; If the host disappears (quits / crashes / network dies), every client
; goes back to the main menu instead of being stuck in a dead room.
Function NetCheckHostGone()
	If NetMode <> NET_CLIENT Then Return
	If NetStream = 0 Then Return
	If MilliSecs() - NetLastHostMsg > 3500 Then
		NetKicked = True
		NetKickMsg = "The host left the room. Returned to the main menu."
	End If
End Function

; ---------------------------------------------------------------------
; UDP room discovery
; ---------------------------------------------------------------------
Function NetDiscStartHost()
	If NetUdp <> 0 Then NetDiscStop()
	NetUdp = CreateUDPStream(NET_DISC_PORT)
	If NetUdp <> 0 Then NetDiscMode = 1
End Function

Function NetDiscStartBrowse()
	If NetDiscMode = 2 And NetUdp <> 0 Then Return
	NetDiscStop()
	NetClearRooms()
	NetUdp = CreateUDPStream(0)
	If NetUdp <> 0 Then
		NetDiscMode = 2
		NetDiscBuildScanList()
	End If
End Function

Function NetDiscStop()
	If NetUdp <> 0 Then CloseUDPStream NetUdp
	NetUdp = 0
	NetDiscMode = 0
	NetScanIdx = 0
	NetScanT = 0
End Function

Function NetClearRooms()
	Local rm.NetRoom
	For rm = Each NetRoom : Delete rm : Next
End Function

; dotted "192.168.1.7" -> Blitz3D integer IP
Function NetIPFromDotted%(d$)
	Local p1% = Instr(d$, ".")
	If p1 = 0 Then Return 0
	Local p2% = Instr(d$, ".", p1 + 1)
	Local p3% = Instr(d$, ".", p2 + 1)
	If p2 = 0 Or p3 = 0 Then Return 0
	Local a% = Int(Left(d$, p1 - 1))
	Local b% = Int(Mid(d$, p1 + 1, p2 - p1 - 1))
	Local c% = Int(Mid(d$, p2 + 1, p3 - p2 - 1))
	Local e% = Int(Mid(d$, p3 + 1))
	Return (a Shl 24) Or (b Shl 16) Or (c Shl 8) Or e
End Function

Function NetDiscAddBase(ip%)
	Local d$ = DottedIP$(ip)
	Local lp% = 0, i%
	For i = 1 To Len(d$)
		If Mid(d$, i, 1) = "." Then lp = i
	Next
	If lp = 0 Then Return
	Local prefix$ = Left(d$, lp) + "0"
	Local base% = NetIPFromDotted(prefix)
	For i = 0 To NetScanIPs - 1
		If NetScanBase(i) = base Then Return
	Next
	If NetScanIPs < 15 Then
		NetScanBase(NetScanIPs) = base
		NetScanIPs = NetScanIPs + 1
	End If
End Function

Function NetDiscBuildScanList()
	NetScanIPs = 0
	Local c% = CountHostIPs("")
	Local i%
	For i = 1 To c
		NetDiscAddBase HostIP(i)
	Next
	; also probe the loopback so two instances on one PC still see each other
	NetDiscAddBase $7F000001
	NetScanIdx = 0
	NetScanT = 0
End Function

; spread the /24 sweep over frames so the menu never stutters
Function NetDiscStep()
	If NetUdp = 0 Or NetDiscMode <> 2 Then Return
	If NetScanIPs = 0 Then NetDiscBuildScanList()

	If NetScanIdx >= 0 Then
		Local budget% = 8
		While budget > 0 And NetScanIdx < NetScanIPs * 254
			Local bi% = NetScanIdx / 254
			Local oct% = (NetScanIdx Mod 254) + 1
			WriteLine NetUdp, "SCP173Q"
			SendUDPMsg NetUdp, NetScanBase(bi) Or oct, NET_DISC_PORT
			NetScanIdx = NetScanIdx + 1
			budget = budget - 1
		Wend
		If NetScanIdx >= NetScanIPs * 254 Then
			NetScanIdx = -1
			NetScanT = 90      ; ~1.3 s before the next sweep
		End If
	Else
		NetScanT = NetScanT - FpsFactor
		If NetScanT <= 0 Then NetScanIdx = 0
	End If
End Function

Function NetDiscHandle(msg$, ip%, port%)
	Local kind$ = SplitPart(msg$, 1)

	If kind = "SCP173Q" And NetDiscMode = 1 Then
		; somebody is looking for rooms - tell them about this one
		Local players% = 1
		Local rp.RemotePlayer
		For rp = Each RemotePlayer : players = players + 1 : Next
		WriteLine NetUdp, "SCP173R|" + NetRoomName + "|" + Str(NET_PORT) + "|" + Str(players)
		SendUDPMsg NetUdp, ip, port

	ElseIf kind = "SCP173R" And NetDiscMode = 2 Then
		Local ipStr$ = DottedIP$(ip)
		Local name$ = SplitPart(msg$, 2)
		Local tcpPort% = Int(SplitPart(msg$, 3))
		Local pl% = Int(SplitPart(msg$, 4))
		Local found% = False
		Local rm.NetRoom
		For rm = Each NetRoom
			If rm\ip = ipStr And rm\port = tcpPort Then
				found = True
				Exit
			End If
		Next
		If Not found Then
			rm = New NetRoom
			rm\ip = ipStr
			rm\port = tcpPort
		End If
		rm\name = name
		rm\players = pl
		rm\seen = MilliSecs()
	End If
End Function

Function NetDiscPoll()
	If NetUdp = 0 Then Return

	If NetDiscMode = 2 Then
		NetDiscStep()
		; forget rooms that stopped answering
		Local rm.NetRoom
		For rm = Each NetRoom
			If MilliSecs() - rm\seen > 4000 Then Delete rm
		Next
	End If

	Local ip% = RecvUDPMsg(NetUdp)
	Local guard% = 0
	While ip <> 0 And guard < 128
		guard = guard + 1
		Local sport% = UDPMsgPort(NetUdp)
		Local msg$ = ""
		While ReadAvail(NetUdp) > 0
			msg = msg + ReadLine$(NetUdp)
		Wend
		If msg <> "" Then NetDiscHandle msg, ip, sport
		ip = RecvUDPMsg(NetUdp)
	Wend
End Function

; ---------------------------------------------------------------------
; Game data
; ---------------------------------------------------------------------
Function NetApplyLine(line$, rp.RemotePlayer)
	Local kind$ = SplitPart(line$, 1)
	If kind = "P" Then
		Local dest.RemotePlayer = rp
		If NetMode = NET_CLIENT And NetHostAvatar <> Null Then dest = NetHostAvatar
		dest\x = Float(SplitPart(line$, 2))
		dest\y = Float(SplitPart(line$, 3))
		dest\z = Float(SplitPart(line$, 4))
		dest\yaw = Float(SplitPart(line$, 5))
		dest\observing = Int(SplitPart(line$, 6))
		dest\lastSeen = MilliSecs()
		If NetMode = NET_CLIENT Then NetLastHostMsg = MilliSecs()
		If dest\obj <> 0 Then
			PositionEntity dest\obj, dest\x, dest\y + 0.9, dest\z
			RotateEntity dest\obj, 0, dest\yaw, 0
		End If
	ElseIf kind = "S" And NetMode = NET_CLIENT Then
		; host is authoritative about SCP-173 and the breach door
		Local sx# = Float(SplitPart(line$, 2))
		Local sz# = Float(SplitPart(line$, 3))
		PositionEntity N173_Collider, sx, 0.35, sz, True
		ResetEntity N173_Collider
		N173_Active = True
		; Update173() is only run by the host, so the client must move the
		; visible statue here as well
		If N173_Obj <> 0 Then PositionEntity N173_Obj, sx, 0.03, sz
		Local dOpen% = Int(SplitPart(line$, 4))
		Local dState# = Float(SplitPart(line$, 5))
		If dOpen <> 0 And DoorOpened = 0 Then
			DoorOpened = True
			PlaySound DoorOpen173Snd
			FX_OnBreach()
		End If
		If DoorOpened Then
			DoorOpenState = dState
			PositionEntity DoorLeft,  288 * RoomScale, 0, 384 * RoomScale - 2.2 * DoorOpenState
			PositionEntity DoorRight, 288 * RoomScale, 0, 384 * RoomScale + 2.2 * DoorOpenState
			ResetEntity DoorLeft : ResetEntity DoorRight
		End If
	End If
End Function

Function NetUpdate()
	; keep answering / collecting discovery packets while playing too
	NetDiscPoll()
	If NetMode = NET_OFFLINE Then Return

	; host accepts new players
	If NetMode = NET_HOST Then
		Local s% = AcceptTCPStream(NetServer)
		If s <> 0 Then NetMakeAvatar(s)
	End If

	NetTick = NetTick + 1

	; "I am watching it": eyes open AND the statue inside the view frustum
	Local watching% = False
	If GameState = GS_PLAY And N173_Obj <> 0 Then
		If (BlinkTimer < -16 Or BlinkTimer > -6) Then
			If EntityInView(N173_Obj, Camera) Then watching = True
		End If
	End If

	If NetTick Mod 3 = 0 Then
		Local line$ = "P|" + Str(EntityX(Collider, True)) + "|" + Str(EntityY(Collider, True)) + "|" + Str(EntityZ(Collider, True)) + "|" + Str(EntityYaw(Collider)) + "|" + Str(Abs(watching))
		If NetMode = NET_CLIENT And NetStream <> 0 Then WriteLine NetStream, line$
		Local rp.RemotePlayer
		For rp = Each RemotePlayer
			If rp\stream <> 0 Then WriteLine rp\stream, line$
		Next
		If NetMode = NET_HOST And N173_Active Then
			Local sl$ = "S|" + Str(EntityX(N173_Collider, True)) + "|" + Str(EntityZ(N173_Collider, True)) + "|" + Str(Abs(DoorOpened)) + "|" + Str(DoorOpenState)
			For rp = Each RemotePlayer
				If rp\stream <> 0 Then WriteLine rp\stream, sl$
			Next
		End If
	End If
	If NetTick Mod 10 = 0 Then
		Local hb.RemotePlayer
		For hb = Each RemotePlayer
			If hb\stream <> 0 Then WriteLine hb\stream, "HB"
		Next
	End If

	; read incoming messages
	Local r.RemotePlayer
	For r = Each RemotePlayer
		If r\stream <> 0 Then
			While ReadAvail(r\stream) > 0
				Local l$ = ReadLine$(r\stream)
				r\lastSeen = MilliSecs()
				If NetMode = NET_CLIENT Then NetLastHostMsg = MilliSecs()
				Select l$
					Case "BYE"
						NetKicked = True
						NetKickMsg = "The host left the room. Returned to the main menu."
					Case "K"
						If NetMode = NET_CLIENT Then KillPlayer()
					Case "HB"
						; keepalive - nothing to do
					Default
						If l$ <> "" Then NetApplyLine l$, r
				End Select
			Wend
		End If
	Next

	If NetMode = NET_HOST Then
		; the statue may only move when NOBODY is watching it
		NetRemoteWatching = False
		Local w.RemotePlayer
		For w = Each RemotePlayer
			If w\observing <> 0 Then NetRemoteWatching = True
		Next
		N173ChooseTarget()
	End If

	NetCheckHostGone()
	If NetMsgTimer > 0 Then NetMsgTimer = NetMsgTimer - FpsFactor
End Function

; Host tells one player that 173 caught him.
Function NetKillRemote%(stream%)
	If stream% = 0 Then Return False
	Local rp.RemotePlayer
	For rp = Each RemotePlayer
		If rp\stream = stream% Then
			WriteLine rp\stream, "K"
			rp\observing = 0
			NetMsg = rp\tag + " was killed by SCP-173."
			NetMsgTimer = 220
			Return True
		End If
	Next
	Return False
End Function

Function NetDrawStatus()
	If NetMsgTimer > 0 Then
		SetFont FntSmall
		Color 120, 200, 255
		Text GraphW / 2, 70 * MenuScale, NetMsg, True
		SetFont FntTitle
	End If
	If NetMode <> NET_OFFLINE Then
		SetFont FntSmall
		Color 120, 200, 255
		Text GraphW - 20 * MenuScale, 60 * MenuScale, "LAN: " + NetModeName$(), True

		; subject tags over the other players - a Class-D figure without a
		; label has no meaning, so always show who it is and whether that
		; person is currently keeping 173 frozen
		Local rp.RemotePlayer
		Local camx# = EntityX(Camera, True), camz# = EntityZ(Camera, True)
		Local cyaw# = EntityYaw(Camera, True)
		For rp = Each RemotePlayer
			If rp\obj <> 0 And EntityInView(rp\obj, Camera) Then
				Local dx# = EntityX(rp\obj, True) - camx
				Local dz# = EntityZ(rp\obj, True) - camz
				Local fwd# = -Sin(cyaw) * dx + Cos(cyaw) * dz
				If fwd > 0.5 And fwd < 80 Then
					Local right# = Cos(cyaw) * dx + Sin(cyaw) * dz
					Local sc# = GraphH * 0.75 / fwd
					Local lx% = GraphW / 2 + right * sc
					Local ly% = GraphH / 2 - (EntityY(rp\obj, True) - EntityY(Camera, True)) * sc - 30 * MenuScale
					Color 215, 190, 130
					Text lx, ly, rp\tag, True
					If rp\observing <> 0 Then
						Color 130, 220, 160
						Text lx, ly - 18 * MenuScale, "WATCHING", True
					End If
				End If
			End If
		Next
		SetFont FntTitle
	End If
End Function

Function NetModeName$()
	If NetMode = NET_HOST Then Return "HOST"
	If NetMode = NET_CLIENT Then Return "CLIENT"
	Return "OFFLINE"
End Function
