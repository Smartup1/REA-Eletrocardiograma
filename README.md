# SnapECG Reader

App Android que le o monitor de ECG SnapECG por **cabo USB (OTG)**, mostra o ECG ao vivo e a frequencia
cardiaca, grava em CSV e exporta.

Protocolo obtido por analise estatica do app oficial (USB = FTDI FT231X 0403:6015, 115200 8N1).
Nao e dispositivo medico validado: nao use para diagnostico.

## Gerar o APK (sem instalar nada)
1. Suba esta pasta para o seu repositorio no GitHub (branch `main`).
   Se existirem `blank.yml` / `main.yml` em `.github/workflows/`, apague-os.
2. Aba **Actions** -> workflow **Build APK** -> espere ficar verde (ou clique **Run workflow**).
3. Abra a execucao -> **Artifacts** -> baixe `SnapECG-debug-apk` -> descompacte -> `app-debug.apk`.
4. Copie para o celular e instale (permita "instalar apps desconhecidos").

## Usar
Plugue o aparelho no celular com cabo OTG, abra o app e aceite a permissao USB.
- **Gravar** salva em CSV (continua no arquivo mesmo se o app fechar). **Parar gravacao** fecha o arquivo.
- **Exportar** abre o compartilhamento do Android com o ultimo CSV.
- CSV: `t_s,raw,uv_raw,uv_filtrado,eletrodo_solto,marcapasso,qrs`. Linhas `#` sao metadados
  (`pandas.read_csv("arq.csv", comment="#")`). Arquivos ficam em `Android/data/com.snapecg.reader/files/`.

## Filtro
mediana de 5 amostras (tira picos/artefatos) -> passa-alta 0,5 Hz -> notch 60 Hz -> passa-baixa 40 Hz.
Rede eletrica diferente de 60 Hz: ajuste `mains` em `EcgFilter` (Dsp.kt).

## Escala do grafico
Papel de ECG calibrado pelo DPI real da tela: **25 mm/s e 10 mm/mV** (grade de 1 mm e 5 mm).
Toque no grafico muda o ganho (5/10/20 mm/mV); toque longo muda a velocidade (12,5/25/50 mm/s).
Em retrato cabem ~2,5 s a 25 mm/s: gire o celular ou use 12,5 mm/s para ver mais batimentos.
