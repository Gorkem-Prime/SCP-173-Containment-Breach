# SCP-173 Containment Breach — by GorkemPrime

Orijinal **SCP - Containment Breach** kaynak kodundan sadece SCP-173 mekaniğini,
modelini/texture'larını ve ilk spawn haritasını (hücreler + SCP-173 odası) içeren
tek başına çalışan bir Blitz3D mini oyunu.

## İçerik

- **Harita:** `GFX\map\173bright_opt.rmesh` — orijinal oyundaki ilk spawn haritası
  (Class-D hücreleri ve SCP-173'nin muhafaza odası).
- **SCP-173:** `GFX\npcs\173_2.b3d`, `GFX\173box.b3d` ve texture'ları
  (`173texture.jpg`, `173_Norm.jpg`, `173_Spec.jpg`, `173h.pt`).
- **Mekanik:** Orijinal `NPCs.bb`'deki bakma/göz kırpma kuralı, kovalama,
  teleport ve öldürme kodu taşındı. Gözler kapalıyken veya SCP-173 görüş
  alanı dışındayken hareket eder; baktığınız sürece donar. 173 artık duvar
  arkasına da geçebilir: her adımı 360° ışın (raycast) taramasıyla seçer,
  duvarların içinden geçmez ve haritanın dışına çıkamaz.
- **Sesler:** `SFX\SCP\173\` (StoneDrag, Rattle, NeckSnap) ve
  `SFX\Door\DoorOpen173.ogg` — orijinal dosyalar.
- Diğer SCP'ler, eşyalar, kayıt sistemi ve olaylar dahil **değildir**.

## Nasıl oynanır

1. `SCP-173 Containment Breach.exe` dosyasını çalıştırın (veya `Main.bb`'yi
   Blitz3D 1.108 ile derleyin).
2. Açılışta **"SCP-173 Containment Breach by GorkemPrime"** yazısı görünür.
3. Menüden **PLAY** ile oyuna girin. Hücrede spawn olursunuz.
4. **WASD** ile yürüyün, **Shift** ile koşun, **Ctrl** ile çömelin.
5. Fare ile etrafa bakın. Gözünüzü kırpmak için **Space** (veya ayarlardan
   belirlediğiniz tuş) — basılı tutarsanız gözleriniz kapalı kalır.
6. Hücre kapıları ve diğer kapılar **orijinal 1. haritanın kodundaki** konumlara
   yerleştirildi. Sadece spawn olduğunuz hücrenin kapısı açık başlar; diğer
   bütün hücre kapıları kapalıdır. Hücre kapısındaki sarı butona yaklaşıp **E**
   ile kapıyı açıp kapatabilirsiniz. Diğer hücre kapıları kapalı kalır,
   otomatik açılmaz. Hücre dışındaki bütün kapılar oyundan çıkarıldı
   (yalnızca SCP-173'nin muhafaza kapısı kaldı). Kapılar orijinal
   `UpdateDoors()` mantığıyla yana kayarak açılır.
   Hareket: A/D (sola/sağa) ve W/S (ileri/geri) tek yönde çalışır; aynı anda
   basıldığında A/D önceliklidir, çapraz kayma olmaz.
7. SCP-173'nin muhafaza kapısına yaklaşınca kapı açılır ve SCP-173 aktif olur.
   Kapı bir kez açıldıktan sonra açık kalır, tekrar kapanmaz. Ölene kadar
   kaçmaya devam edersiniz. SCP-173'ye dokunabilirsiniz (sensor hitbox) ama
   asla itemezsiniz.
8. Ölünce **Space** veya **Enter** ile yeniden spawn olursunuz (respawn).
   Tur sıfırlanmaz: harita, açılan kapı ve mermi durumu korunur; SCP-173 öldükten
   sonra haritanın bir köşesine çekilip orada bekler.
9. **ESC** ile oyunu durdurup (RESUME / RESTART ROUND / MAIN MENU / LEAVE LAN / QUIT)
   menüsüne erişebilirsiniz. **F11** ile 1920x1080 tam ekran / 1280x720
   pencere modu arasında geçiş yapabilirsiniz.
10. Menülerde fareyle tıklama veya klavye ile seçim yapılabilir: ana menüde
   `1=PLAY 2=SETTINGS 3=MULTIPLAYER 4=QUIT`, ESC menüsünde `1=RESUME 2=RESTART 3=MAIN MENU
   4=QUIT`, ayarlarda `1=fullscreen 2=çözünürlük 3/4=hassasiyet 5/6=ses
   7=invert 8=göz kırpma tuşu 9=geri`.
10. Yürüme ve koşma sesleri orijinal `SFX\Step\` dosyalarından çalınır.
11. Grafikler: kenar yumuşatma (anti-alias) ve ek ışıklar. Göz kırpma ölçeri
    orijinal `DrawGUI()` kodundan alınıp sol alta yerleştirildi. Fare
    hassasiyeti orijinal seviyeye (`mouselook_x_inc = 0.3`) yükseltildi.
12. **P90 (`P90.bb`):** Oyun içinde **1** tuşu P90'ı CS tarzı alttan yukarı
    kaldırır / geri indirir. **Sol fare tuşunu basılı tutmak** otomatik ateş
    eder: namlu alevi, ileri uçan izli mermi, duvara çarpınca kıvılcım.
    Geri tepme **sadece silahadır ve geriye doğrudur** (kamera tepmez).
    **R** ile şarjör değiştirme animasyonu oynar. Sağ altta mermi/rezerv
    sayacı gösterilir. Yürürken/koşarken silah sallanır, dönünce geride kalır.
    Model `SimpleProjectBlitz3D` demosundan; sesler gerçek CS:GO P90
    ses dosyalarıdır (`sourcesounds/csgo`).
13. **Görsel efektler (`FX.bb`):** kamera titremesi/roll, koşu FOV'u, yürüyüş
    sallanması, titreyen floresan ışıklar, kameraya bağlı el feneri ve ihlal
    anında devreye giren kırmızı acil durum ışıkları **her zaman açıktır**.
    Ekranda parçacık/grain, vignette, tarama çizgisi veya yansıma yoktur.
    Konsol/uçma modu kaldırıldı. SCP-173 her kare
    harita sınırları içine sabitlenir, düşemez ve dışarı çıkamaz.
14. **MULTIPLAYER** menüsünden LAN üzerinde oda oluşturabilir veya açık odalara
    katılabilirsiniz. **JOIN ROOM** IP adresi sormaz; aynı ağdaki odaları
    otomatik tarar ve listede gösterir (REFRESH ile yenilenir). Sunucu `5555`
    portunu açar; SCP-173'ü host yönetir. Diğer oyuncular turuncu D-class
    kıyafetleriyle, üstlerinde `D-...` / `HOST` etiketiyle görünür; biri 173'ü
    izliyorsa etiketinde `WATCHING` yazar. SCP-173 yalnızca **hiç kimse** ona
    bakmadığında hareket eder ve her zaman en yakın oyuncuyu değil, gördüğü /
    son gördüğü oyuncuyu kovalar. Host odadan çıkarsa (BYE veya bağlantı
    kopması) bütün oyuncular ana menüye atılır. **ESC** ile açılan menüden
    `MAIN MENU` LAN bağlantısını kapatmaz; bağlantıyı kapatmak için `LEAVE LAN`
    (`6`) kullanılır. Kısayollar: `1=CREATE ROOM 2=JOIN ROOM 3=BACK
    4=LEAVE LAN`.

## Ayarlar

`SETTINGS` menüsünden fare hassasiyeti, ses seviyesi, fare Y eksenini tersleme,
göz kırpma tuşu, çözünürlük ve tam ekran ayarlanabilir. Değişiklikler
`options.ini` dosyasına kaydedilir (çözünürlük/tam ekran için yeniden başlatma
gerekir).

Görsel efektlerin aç/kapat tuşu yoktur; hepsi her zaman açıktır. `options.ini`
dosyasındaki `[fx]` bölümü kullanılmaz (artık gerekli değildir).

## Derleme

Blitz3D 1.108 kuruluysa `Main.bb` dosyasını açıp F5 ile derleyin. `fmod.dll`
dosyası aynı klasörde bulunmalıdır (oyun sesleri için). `mavless_bin.zip`
içindeki `blitzcc.exe` ile komut satırından da derlenebilir:

```
blitzcc -o "SCP-173 Containment Breach.exe" Main.bb
```

## Lisans / Telif

Orijinal oyunun kaynak kodu ve asset'leri Creative Commons
Attribution-ShareAlike 3.0 lisanslıdır. Bu proje yalnızca kişisel/öğrenme
amaçlı bir türev çalışmadır. Orijinal yapımcılara (Undertow Games) teşekkürler.
