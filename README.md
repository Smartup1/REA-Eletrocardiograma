# SnapEcgUsb

App Android (Kotlin, sem Compose) que lê o monitor de ECG SnapECG pelo USB (FTDI FT231X, 115200 8N1).

## Como usar
1. Android Studio (Koala ou mais novo): File > Open > pasta `SnapEcgUsb`. Ele baixa o Gradle 8.7 sozinho.
2. Ative a depuração USB no celular e rode o app (Run).
3. Ligue o ECG ao celular com cabo/adaptador OTG. O Android abre o app e pede permissão USB.
4. Toque em Conectar. O log mostra os primeiros bytes recebidos (`RX[...]`) para diagnóstico.
5. O CSV (t_s,uv,lead_off,pacemaker) vai para Download/SnapEcgUsb/.

Aviso: uso pessoal/experimental, não é dispositivo médico validado.
