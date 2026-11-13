# Bloco · Servidor Bedrock no celular

O Bloco 2.0 é um aplicativo Android independente que tenta executar o servidor
**oficial** de Minecraft Bedrock no próprio celular ARM64, sem hospedagem
externa, Docker, Termux ou outro aplicativo instalado. O APK contém o ambiente
necessário; depois que a pessoa aceita os termos, ele consulta a Microsoft e
baixa o servidor oficial diretamente da Mojang. O jogo e o servidor da Mojang
não são distribuídos neste repositório.

## Antes de usar

- A edição 2.0.3 é um **teste paralelo** (`br.com.bloco.servidor.teste`): instala
  ao lado do Bloco anterior sem removê-lo. Os dados são separados. Para testar
  uma cópia do mundo antigo, exporte `.mcworld` no app anterior e importe no novo;
  guarde também os arquivos dos packs. Não rode os dois servidores na mesma porta.
- Android 8.0+, aparelho e sistema ARM64; recomendam-se 8 GB ou mais de RAM
  para tentar atender 10 jogadores e pelo menos 3 GB livres além dos mundos.
- Em Wi-Fi, o app mostra o IPv4 local e a porta configurável, 19132 por padrão.
  O Bedrock atual usa NetherNet: TCP para sinalização e UDP para jogar. O
  diagnóstico verifica uma resposta local, não a entrada de um jogador. Com a
  descoberta LAN desativada, pode haver HTTP 200 sem metadados; essa resposta
  não identifica o serviço com certeza. Para
  tentar acesso externo é preciso autenticação, IPv4 público e encaminhamento
  TCP e UDP. Dependendo do NAT, pode ser necessário anunciar o endereço público
  do UDP, algo que o app não configura automaticamente. O aplicativo **não cria
  IP público**; CGNAT e bloqueios podem impedir conexões externas.
- Se houver falha de porta, confira o diagnóstico em "Ver detalhes", feche
  outros servidores e experimente outra porta em "Configurações" (por exemplo
  19142). Desativar descoberta automática permite testar sem ela e usar o IP
  manualmente. O aviso do Bedrock não prova que a porta esteja ocupada; o
  próprio Android pode impedir alguma operação e a solução não é garantida.
- 10 vagas são configuradas por padrão, mas desempenho e compatibilidade
  variam. O celular precisa permanecer ligado e conectado; Android pode
  interromper processos em segundo plano, portanto não há garantia de 24h.
- Por compatibilidade, a autenticação Microsoft começa desligada e exige
  confirmação antes de cada início. Nesse modo nomes podem ser falsificados:
  use apenas em rede confiável e não exponha o servidor à internet. Pode-se
  ativá-la nas configurações, mas ela pode não funcionar neste ambiente.
- A aplicação é experimental: a pilha e o servidor real foram exercitados em
  Linux ARM64, e o APK de distribuição é testado num Android ARM64 em contêiner
  com SELinux desligado. **Não há teste em telefone físico, login real nem com
  10 jogadores reais**. Box64 e PRoot podem falhar em alguns aparelhos.
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
  configuração, exclusão mútua, runtime, diagnóstico de portas e probes locais
  de NetherNet e RakNet, inclusive respostas malformadas e portas ocupadas.
  Requer JDK 21 e baixa uma cópia de teste de `org.json` do GitHub; ela não é
  distribuída no APK (Android fornece a própria implementação).
- Em Linux ARM64, `scripts/build-mobile-runtime.sh <pasta>` compila o fork
  Termux do PRoot como static PIE com musl e o Box64, baixa Zig, Ubuntu Base e
  bibliotecas com integridade fixada e cria `runtime.zip`. Executa um teste de
  filtros seccomp herdados no PRoot real; esse filtro simulado não representa
  todas as políticas Android. O modo direto desativa o registro rseq da glibc;
  no modo compatível, o PRoot trata alguns traps de processos filhos sem
  desabilitar o filtro Android. O PRoot usa `PROOT_NO_SECCOMP=1` apenas para
  desativar a própria otimização, não a política de segurança do sistema.
  O Box64 incluído tem um patch opcional de reuso de endereço TCP para o
  processo NetherNet, sem alterar UDP ou habilitar SO_REUSEPORT. Um segundo
  patch protege os callbacks de limpeza contra registros simultâneos, uma
  corrida relatada em Box64 v0.4.4 (issue `ptitSeb/box64#4574`). O build testa
  as listas com threads, reentrada e fork, e testa um guest x86-64 pelo Box64 e
  PRoot. Também verifica o reinício TCP e a exclusão de listener concorrente;
  os patches e o código-fonte acompanham a distribuição. Isso não comprova que
  a corrida seja a causa de uma falha específica no aparelho.
  `scripts/test-mobile-java.sh <runtime.zip>` usa a mesma lógica do app para
  preparar o ambiente, baixa o servidor oficial atual e testa inicialização,
  sinalização NetherNet TCP (ou RakNet em versões antigas), console, parada e
  persistência do mundo nos perfis direto e PRoot. Para NetherNet, também testa
  uma porta TCP ocupada, porta alternativa e PRoot sob filtro ARM64 simulado;
  a autenticação é apenas diagnóstico, sem login de jogador.
- A CI instala o APK extraído do ZIP ao lado da versão 2.0.2 em Android 13 e 15
  ARM64 em contêiner, verifica que os dados antigos continuam intactos, inicia
  com autenticação desligada, verifica sinalização TCP nas portas padrão e
  alternativa e preserva o mundo de teste. Também testa o PRoot do APK com filtro
  simulado no host e sob o UID do app no Android. Os contêineres usam SELinux
  desligado e não substituem aparelho físico nem políticas específicas de OEM.
  Os testes de sinalização não verificam o tráfego UDP nem um login real.
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
