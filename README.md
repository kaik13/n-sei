# Bloco · Servidor Bedrock no celular

O Bloco 2.0 é um aplicativo Android independente que tenta executar o servidor
**oficial** de Minecraft Bedrock no próprio celular ARM64, sem hospedagem
externa, Docker, Termux ou outro aplicativo instalado. O APK contém o ambiente
necessário; depois que a pessoa aceita os termos, ele consulta a Microsoft e
baixa o servidor oficial diretamente da Mojang. O jogo e o servidor da Mojang
não são distribuídos neste repositório.

## Antes de usar

- Android 8.0+, aparelho e sistema ARM64; recomendam-se 8 GB ou mais de RAM
  para tentar atender 10 jogadores e pelo menos 3 GB livres além dos mundos.
- Em Wi-Fi, o app mostra o IPv4 local e a porta UDP 19132. Para acesso pela
  internet, a rede deve ter IPv4 público e encaminhamento UDP no roteador até
  o celular. O aplicativo **não cria IP público**; CGNAT e bloqueios da
  operadora podem impossibilitar conexões externas sem rede/serviço adicional.
- 10 vagas são configuradas por padrão, mas desempenho e compatibilidade
  variam. O celular precisa permanecer ligado e conectado; Android pode
  interromper processos em segundo plano, portanto não há garantia de 24h.
- A aplicação é experimental: a pilha e o servidor real foram exercitados em
  Linux ARM64 na CI, mas **não há teste em telefone Android físico nem com 10
  jogadores reais**. O Bedrock oficial é x86-64; o app usa Box64 e PRoot, e
  alguns kernels, fabricantes ou versões Android podem impedir sua execução.
- O target SDK 28 permite executar o ambiente privado em aparelhos compatíveis;
  é uma distribuição direta, não destinada à Play Store. A chave de teste é
  temporária e uma nova assinatura pode exigir desinstalar o app: **exporte o
  mundo e guarde os packs antes de desinstalar**, pois os dados locais somem.

Leia `distribution-mobile/LEIA-ME.txt` para instalação, rede, atualização,
segurança, cópias de segurança, limites e licenças. O ZIP final tem apenas o
APK, o guia, as licenças, fontes legais e os hashes. Importação de mundos `.mcworld`, pacotes
`.mcpack`/`.mcaddon`, exportação e restauração exigem que o servidor esteja
parado. Pacotes protegidos ou experimentais podem ser incompatíveis.

A versão anterior de painel remoto foi mantida em código para referência; seu
guia está em [`docs/PAINEL-LEGADO-1.1.0.md`](docs/PAINEL-LEGADO-1.1.0.md).
O ZIP 1.1.0 é legado e não hospeda o servidor no celular.

## Desenvolvimento e testes

- `npm ci && npm test && npm run build`: testes e build do painel legado.
- `scripts/test-mobile-java.sh`: testa extração segura, mundo, backups, packs,
  configuração, exclusão mútua, instalação do runtime e teste RakNet local.
  Requer JDK 21 e baixa uma cópia de teste de `org.json` do GitHub; ela não é
  distribuída no APK (Android fornece a própria implementação).
- Em Linux ARM64, `scripts/build-mobile-runtime.sh <pasta>` compila PRoot e
  Box64, baixa Ubuntu Base e bibliotecas com hashes fixados e cria `runtime.zip`.
  `scripts/test-mobile-java.sh <runtime.zip>` usa a mesma lógica do app para
  preparar o ambiente, baixa o servidor oficial atual e testa inicialização,
  resposta UDP RakNet, console, parada e persistência do mundo nos perfis
  direto e PRoot. Usa autenticação desligada apenas no teste local.
- Copie o `runtime.zip` verificado para
  `mobile/android/app/src/main/assets/runtime.zip`; com Java 21, Android SDK
  Platform 34 e Build Tools 34.0.0 instalados, rode `npm run build:android`.
  Copie também a pasta `fontes/` para `.mobile-runtime/fontes/` (ou defina
  `BLOCO_SOURCES_DIR`); `npm run package:release` cria `Zips/bloco-<versão>-android-celular.zip`
  sem sobrescrever arquivo existente. `BLOCO_ZIP_DIR` muda o diretório de saída.
- A assinatura padrão é uma chave temporária de desenvolvimento. Para usar
  uma chave própria já existente, configure fora do repositório `BLOCO_KEYSTORE`,
  `BLOCO_KEY_ALIAS`, `BLOCO_KEYSTORE_PASSWORD` e `BLOCO_KEY_PASSWORD`.
  Nunca coloque chaves ou senhas no Git ou no ZIP.

Os fluxos do GitHub Actions testam e compilam na revisão correta; seus
artefatos expiram e podem ser ZIPs externos contendo o ZIP final. As licenças
são documentadas em `mobile/licencas/` e o app não é afiliado à Mojang.
