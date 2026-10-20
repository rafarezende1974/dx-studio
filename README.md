# DX Studio — mesa de corte portátil (Android)

App Android estilo OBS para tablet (pensado para a Galaxy Tab S9+), focado em
**gravação** nesta v1: interface com **Prévia** e **Programa**, troca de
câmeras ao vivo e gravação do programa já com os cortes, em um único MP4.

## Fontes

- **CAM 1 / CAM 2** — plaquinhas de captura HDMI→USB (padrão UVC) ou webcams
  USB ligadas num hub USB-C **alimentado** (com fonte; de preferência com
  pass-through pra carregar o tablet junto).
- **TABLET** — câmera interna do tablet (traseira), como terceira fonte.

Motor de vídeo USB: AUSBC 3.2.7 (libuvc) — as tags 3.3.x estão quebradas no JitPack. Preview 1280×720 por câmera; a codificação H.264 é feita por encoder próprio do app (UvcEncoder), alimentado pelos frames NV21 do preview, porque o gravador interno da 3.2.7 exige WRITE_EXTERNAL_STORAGE (indisponível no Android 13+).

## Como operar

1. Abra o app e aceite as permissões de câmera e microfone.
2. Conecte o hub com as plaquinhas — o Android pede permissão **por
   dispositivo USB**; aceite em cada uma.
3. Toque numa fonte para mandá-la para a **Prévia** (verde).
4. **TRANSIÇÃO** troca Prévia ⇄ Programa (vermelho). Toque duplo numa fonte
   faz corte direto para o programa. O botão FADE liga/desliga a transição
   suave na tela.
5. **Áudio** é fixo e independente da câmera: escolha a entrada no topo
   (microfone do tablet ou dispositivo USB — interface, mesa, ou o áudio USB
   quando a plaquinha expõe UAC) e acompanhe o medidor de nível.
6. **● REC** grava o Programa (com os cortes) em
   `Filmes/DXStudio/DXStudio_<data>_<hora>.mp4`, 720p30 H.264 + AAC.
   Na troca de câmera durante a gravação, o arquivo espera o próximo keyframe
   da nova fonte (até ~1s de quadro congelado no corte — comportamento
   esperado nesta versão).
7. **GRAVAÇÕES** lista os vídeos: toque para assistir, toque longo para
   compartilhar ou apagar.

## Limites conhecidos da v1

- Máximo de 2 câmeras USB simultâneas (+ a interna).
- Gravação em 720p30 (decisão de estabilidade/banda USB; 1080p fica para
  teste posterior).
- O app precisa ficar aberto na tela durante a gravação (a tela não apaga
  sozinha enquanto o app está aberto).
- Transmissão ao vivo (RTMP) ficou fora da v1 — entra na v2 sobre o mesmo
  motor de stream.

## Roteiro de teste sugerido (primeira instalação)

1. Só a câmera interna: abre como TABLET, vai para o programa, REC de 30s,
   confira o arquivo em Gravações (imagem **na orientação certa** e com som).
2. Uma plaquinha no hub: aceita a permissão USB, confira a imagem da CAM 1,
   alterne Prévia/Programa algumas vezes (a imagem **não pode sumir** ao
   trocar de monitor), REC de 1 min alternando com o TABLET.
3. Duas plaquinhas: confira as duas ao mesmo tempo, cortes entre elas
   gravando, e o áudio contínuo durante os cortes.
4. Confira se o MP4 final toca no player do tablet e no PC.

## Build

APK compilado via GitHub Actions (ver `BUILD-WORKFLOW.yml.txt`).
Pacote: `br.com.rafarezende.dxstudio` · minSdk 29 · targetSdk 34.
