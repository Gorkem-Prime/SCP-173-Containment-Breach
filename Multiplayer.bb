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
; The host runs SCP-173's AI and sends its position to clients. Every
; player sends their own position, so everybody sees an avatar cube for
; the other players.
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

Type RemotePlayer
	Field stream%
	Field obj%
	Field x#, y#, z#, yaw#
End Type

Type NetRoom
	Field name$
	Field ip$
	Field port%
	Field players%
	Field seen%
End Type

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
Function NetMakeAvatar.RemotePlayer(stream%)
	Local rp.RemotePlayer = New RemotePlayer
	rp\stream = stream
	If NetAvatarMesh = 0 Then NetAvatarMesh = CreateCube()
	rp\obj = CopyEntity(NetAvatarMesh)
	ScaleEntity rp\obj, 0.35, 1.7, 0.35
	EntityColor rp\obj, 70, 140, 230
	HideEntity rp\obj
	Return rp
End Function

; ---------------------------------------------------------------------
; Host / join
; ---------------------------------------------------------------------
Function NetStartHost%()
	NetServer = CreateTCPServer(NET_PORT)
	If NetServer = 0 Then
		NetMsg = "Could not open port " + NET_PORT
		NetMsgTimer = 300
		Return False
	End If
	NetMode = NET_HOST
	NetMsg = "Room created. Waiting for players on port " + NET_PORT
	NetMsgTimer = 400
	NetDiscStartHost()
	Return True
End Function

Function NetJoinRoom%(ip$)
	If ip$ = "" Then ip$ = "127.0.0.1"
	NetStream = OpenTCPStream(ip$, NET_PORT)
	If NetStream = 0 Then
		NetMsg = "Could not connect to " + ip$
		NetMsgTimer = 300
		Return False
	End If
	NetIP = ip$
	NetMode = NET_CLIENT
	NetMakeAvatar(NetStream)
	NetMsg = "Connected to " + ip$
	NetMsgTimer = 300
	Return True
End Function

Function NetDisconnect()
	Local rp.RemotePlayer
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
	NetDiscStop()
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
		rp\x = Float(SplitPart(line$, 2))
		rp\y = Float(SplitPart(line$, 3))
		rp\z = Float(SplitPart(line$, 4))
		rp\yaw = Float(SplitPart(line$, 5))
		If rp\obj <> 0 Then
			PositionEntity rp\obj, rp\x, rp\y + 0.9, rp\z
			RotateEntity rp\obj, 0, rp\yaw, 0
			ShowEntity rp\obj
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
	If NetTick Mod 3 = 0 Then
		Local line$ = "P|" + Str(EntityX(Collider, True)) + "|" + Str(EntityY(Collider, True)) + "|" + Str(EntityZ(Collider, True)) + "|" + Str(EntityYaw(Collider))
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

	; read incoming messages
	Local r.RemotePlayer
	For r = Each RemotePlayer
		If r\stream <> 0 Then
			While ReadAvail(r\stream) > 0
				Local l$ = ReadLine$(r\stream)
				If l$ <> "" Then NetApplyLine l$, r
			Wend
		End If
	Next

	If NetMsgTimer > 0 Then NetMsgTimer = NetMsgTimer - FpsFactor
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
		SetFont FntTitle
	End If
End Function

Function NetModeName$()
	If NetMode = NET_HOST Then Return "HOST"
	If NetMode = NET_CLIENT Then Return "CLIENT"
	Return "OFFLINE"
End Function
