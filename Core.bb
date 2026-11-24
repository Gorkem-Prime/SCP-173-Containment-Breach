; =====================================================================
; SCP-173 Containment Breach  -  CORE
; ---------------------------------------------------------------------
; Small, self contained engine for the 173-only game.
; The .rmesh loader below is a faithful, trimmed copy of LoadRMesh()
; from the original SCP: Containment Breach source (MapSystem.bb).
; It loads ONLY geometry + collision; point entities are ignored and
; replaced by our own scripted lights / doors / spawns.
; =====================================================================

; ----- configuration -------------------------------------------------
Global OptionFile$ = "options.ini"

Global GraphW%, GraphH%, FullScreen%, MenuScale#
Global MouseSens#       = 0.0
Global InvertMouse%     = 0
Global SFXVolume#       = 1.0
Global ShowFPS%         = 0
Global FovSetting%      = 0
Global BlinkKey%        = 57   ; Space

; ----- collision -----------------------------------------------------
Const HIT_MAP% = 1, HIT_PLAYER% = 2

; ----- texture cache (same idea as the original Materials type) ------
Type Materials
	Field name$
	Field Diff%
End Type

; ----- network types (declared here so every included file can use them)
Type RemotePlayer
	Field stream%       ; TCP stream handle (0 = the host's broadcast)
	Field obj%          ; the figure drawn for this player
	Field x#, y#, z#, yaw#
	Field observing%    ; eyes open and 173 inside his view frustum
	Field lastSeen%     ; last time data arrived (host drop detection)
	Field tag$          ; label drawn above the figure
End Type

Type NetRoom
	Field name$
	Field ip$
	Field port%
	Field players%
	Field seen%
End Type

; ----- engine --------------------------------------------------------
Global Camera%, Collider%, RoomObj%
Global FpsFactor# = 1.0
Global LastTime%
Global GameTime# = 0.0

; ----- font ----------------------------------------------------------
Global FntTitle%, FntBig%, FntSmall%

; virtual mouse used by menus (works even while the OS pointer is hidden)
Global VirtMouseX# = 0.0, VirtMouseY# = 0.0
Global VirtMouseReady% = False

; =====================================================================
; INI helpers - small, dependency free implementations of the same
; helpers used by the original game.
; =====================================================================

Function INI_Get$(file$, section$, key$)
	Local f% = ReadFile(file$)
	If f = 0 Then Return ""
	Local cur$ = "", line$, eq%, k$
	While Not Eof(f)
		line$ = Trim(ReadLine$(f))
		If Left(line$, 1) = "[" And Right(line$, 1) = "]" Then
			cur$ = Lower(Mid(line$, 2, Len(line$) - 2))
		Else
			eq = Instr(line$, "=")
			If eq > 0 And cur$ = Lower(section$) Then
				k$ = Lower(Trim(Left(line$, eq - 1)))
				If k$ = Lower(key$) Then
					CloseFile f
					Return Trim(Mid(line$, eq + 1))
				End If
			End If
		End If
	Wend
	CloseFile f
	Return ""
End Function

Function INI_Int(file$, section$, key$, def% = 0)
	Local v$ = INI_Get(file$, section$, key$)
	If v$ = "" Then Return def
	Return Int(v$)
End Function

Function INI_Float#(file$, section$, key$, def# = 0.0)
	Local v$ = INI_Get(file$, section$, key$)
	If v$ = "" Then Return def
	Return Float(v$)
End Function

Dim IniLine$(2048)

Function INI_Set(file$, section$, key$, value$)
	Local f% = ReadFile(file$)
	Local n% = 0, i%, line$
	If f <> 0 Then
		While Not Eof(f) And n < 2048
			n = n + 1
			IniLine(n) = ReadLine$(f)
		Wend
		CloseFile f
	End If

	Local cur$ = "", eq%, k$, found% = False
	For i = 1 To n
		line$ = IniLine(i)
		If Left(line$, 1) = "[" And Right(line$, 1) = "]" Then
			cur$ = Lower(Mid(line$, 2, Len(line$) - 2))
		Else
			eq = Instr(line$, "=")
			If eq > 0 And cur$ = Lower(section$) Then
				k$ = Lower(Trim(Left(line$, eq - 1)))
				If k$ = Lower(key$) Then
					IniLine(i) = key$ + " = " + value$
					found = True
				End If
			End If
		End If
	Next

	If Not found Then
		; append a new section/key at the end
		n = n + 1 : IniLine(n) = ""
		n = n + 1 : IniLine(n) = "[" + section$ + "]"
		n = n + 1 : IniLine(n) = key$ + " = " + value$
	End If

	f = WriteFile(file$)
	For i = 1 To n
		WriteLine f, IniLine(i)
	Next
	CloseFile f
End Function

Function SaveSettings()
	INI_Set OptionFile, "options", "width", GraphW
	INI_Set OptionFile, "options", "height", GraphH
	INI_Set OptionFile, "options", "fullscreen", FullScreen
	INI_Set OptionFile, "options", "mouse sensitivity", Str(MouseSens)
	INI_Set OptionFile, "options", "invert mouse y", InvertMouse
	INI_Set OptionFile, "audio", "sound volume", Str(SFXVolume)
	INI_Set OptionFile, "binds", "Blink key", BlinkKey
End Function

; =====================================================================
; Textures
; =====================================================================

Function StripPath$(file$)
	Local name$ = "", i%
	For i = 1 To Len(file$)
		If Mid(file$, i, 1) = "\" Or Mid(file$, i, 1) = "/" Then
			name$ = ""
		Else
			name$ = name$ + Mid(file$, i, 1)
		End If
	Next
	Return name$
End Function

Function GetTex%(name$)
	Local tc.Materials
	For tc = Each Materials
		If tc\name = name$ Then Return tc\Diff
	Next
	Return 0
End Function

Function CacheTex(tex%)
	If tex = 0 Then Return
	Local tc.Materials = New Materials
	tc\name = StripPath(TextureName(tex))
	tc\Diff = tex
End Function

; =====================================================================
; LoadRMesh - reads the ORIGINAL 173bright_opt.rmesh
; (geometry + invisible collision mesh, point entities are skipped)
; =====================================================================
Function LoadRoomMesh(file$)
	Local f% = ReadFile(file$)
	If f = 0 Then RuntimeError "Could not open map: " + file$

	Local header$ = ReadString(f)
	If header$ <> "RoomMesh" And header$ <> "RoomMesh.HasTriggerBox" Then
		RuntimeError file$ + " is not a RoomMesh."
	End If

	; Strip the file name so textures can be loaded relative to the map
	Local dir$ = ""
	Local i%, j%, k%
	For i = 1 To Len(file$)
		If Mid(file$, i, 1) = "\" Or Mid(file$, i, 1) = "/" Then dir$ = Left(file$, i)
	Next

	Local white% = CreateTexture(4, 4, 1, 1)
	ClsColor 255, 255, 255
	SetBuffer TextureBuffer(white)
	Cls
	SetBuffer BackBuffer()
	ClsColor 0, 0, 0

	; the original puts this on texture layer 0 for lightmapped surfaces
	Local ambientTex% = CreateTexture(4, 4, 1)
	TextureBlend ambientTex, 5
	SetBuffer TextureBuffer(ambientTex)
	ClsColor 0, 0, 0
	Cls
	SetBuffer BackBuffer()

	Local opaque% = CreateMesh()
	Local alphaMesh% = CreateMesh()
	Local collisionMeshes% = CreatePivot()
	Local invis% = CreateMesh()
	Local surf%, child%, brush%, tex%[2]
	Local cnt%, flag%, tname$, isAlpha%, v%, p%
	Local x#, y#, z#, u#, vv#, r%, g%, b%

	cnt = ReadInt(f)
	For k = 1 To cnt
		For j = 0 To 1
			flag = ReadByte(f)
			tex[j] = 0
			If flag <> 0 Then
				tname$ = ReadString(f)
				tex[j] = GetTex(tname$)
				If tex[j] = 0 Then
					If flag < 3 Then
						tex[j] = LoadTexture(dir$ + tname$, 1)
					Else
						tex[j] = LoadTexture(dir$ + tname$, 3)
					End If
					If tex[j] <> 0 Then
						If flag = 1 Then TextureBlend tex[j], 5
						If Instr(Lower(tname$), "_lm") <> 0 Then TextureBlend tex[j], 3
						CacheTex tex[j]
					End If
				End If
				If tex[j] <> 0 Then
					isAlpha = 2
					If flag = 3 Then isAlpha = 1
					TextureCoords tex[j], 1 - j
				End If
			End If
		Next

		brush = CreateBrush()
		If isAlpha = 1 Then
			If tex[1] <> 0 Then
				BrushTexture brush, tex[1], 0, 0
			Else
				BrushTexture brush, white, 0, 0
			End If
		ElseIf tex[0] <> 0 And tex[1] <> 0 Then
			; diffuse + lightmap: same layer setup as the original game
			For j = 0 To 1
				BrushTexture brush, tex[j], 0, j + 2
			Next
			BrushTexture brush, ambientTex, 0
		Else
			For j = 0 To 1
				If tex[j] <> 0 Then
					BrushTexture brush, tex[j], 0, j
				Else
					BrushTexture brush, white, 0, j
				End If
			Next
		End If

		child = CreateMesh()
		surf = CreateSurface(child)
		If isAlpha > 0 Then PaintSurface surf, brush
		FreeBrush brush

		v = ReadInt(f)
		For j = 1 To v
			x = ReadFloat(f) : y = ReadFloat(f) : z = ReadFloat(f)
			Local vtx% = AddVertex(surf, x, y, z)
			For i = 0 To 1
				u = ReadFloat(f) : vv = ReadFloat(f)
				VertexTexCoords surf, vtx, u, vv, 0.0, i
			Next
			r = ReadByte(f) : g = ReadByte(f) : b = ReadByte(f)
			VertexColor surf, vtx, r, g, b, 1.0
		Next

		p = ReadInt(f)
		For j = 1 To p
			Local a% = ReadInt(f)
			Local b2% = ReadInt(f)
			Local c% = ReadInt(f)
				AddTriangle surf, a, b2, c
		Next

		; add the visible geometry to the room mesh
		If isAlpha = 1 Then
			AddMesh child, alphaMesh
		Else
			AddMesh child, opaque
		End If
		FreeEntity child

		isAlpha = 0
	Next

	; invisible collision geometry (half a mesh, made double sided)
	cnt = ReadInt(f)
	For k = 1 To cnt
		surf = CreateSurface(invis)
		v = ReadInt(f)
		For j = 1 To v
			x = ReadFloat(f) : y = ReadFloat(f) : z = ReadFloat(f)
			AddVertex surf, x, y, z
		Next
		p = ReadInt(f)
		For j = 1 To p
			Local t1% = ReadInt(f)
			Local t2% = ReadInt(f)
			Local t3% = ReadInt(f)
			AddTriangle surf, t1, t2, t3
			AddTriangle surf, t1, t3, t2
		Next
	Next

	; point entities, screens, lights, props ... are deliberately NOT read.
	CloseFile f
	FreeTexture white
	FreeTexture ambientTex

	; alpha geometry: double sided, then merged into the opaque mesh
	Local flipA% = CopyMesh(alphaMesh)
	FlipMesh flipA
	AddMesh flipA, alphaMesh
	FreeEntity flipA
	AddMesh alphaMesh, opaque
	FreeEntity alphaMesh

	; the visible map geometry is the primary collider (covers the whole
	; room, including the chamber which the hidden mesh does not cover)
	EntityFX opaque, 3
	EntityType opaque, HIT_MAP
	EntityPickMode opaque, 2
	EntityAlpha opaque, 1.0

	EntityAlpha invis, 0.0
	EntityType invis, HIT_MAP
	EntityPickMode invis, 2

	Local root% = CreatePivot()
	CreatePivot(root) ; "meshes" slot (kept for layout compatibility)
	EntityParent opaque, root
	EntityParent invis, root
	EntityParent collisionMeshes, root
	CreatePivot(root) ; "point entities" slot
	CreatePivot(root) ; "solid entities" slot

	Return root
End Function

; =====================================================================
; Small helpers
; =====================================================================

; Min / Max / Distance are custom helpers in the original game (Main.bb)
Function Min#(a#, b#)
	If a < b Then Return a
	Return b
End Function

Function Max#(a#, b#)
	If a > b Then Return a
	Return b
End Function

Function Distance#(x1#, y1#, x2#, y2#)
	Local dx# = x2 - x1, dy# = y2 - y1
	Return Sqr(dx * dx + dy * dy)
End Function

Function Clamp#(v#, lo#, hi#)
	If v < lo Then Return lo
	If v > hi Then Return hi
	Return v
End Function

Function Curve#(target#, current#, speed#)
	Return current + (target - current) / speed
End Function

Function WrapAngle#(a#)
	While a > 180  : a = a - 360 : Wend
	While a < -180 : a = a + 360 : Wend
	Return a
End Function

; The room mesh loader caches loaded textures by name. ClearWorld frees the
; textures themselves, so the cache has to be emptied before the map is
; rebuilt (otherwise a stale handle would be used = access violation).
Function ClearMaterialCache()
	Local tc.Materials
	For tc = Each Materials
		Delete tc
	Next
End Function
