# Bloco Local

Aplicativo Android para preparar e administrar um servidor Minecraft Bedrock **no próprio celular ARM64**, começando por partidas na rede local com até 5 jogadores simultâneos. A versão 3.0.1 é um aplicativo separado das edições anteriores: o identificador `br.com.bloco.local` mantém seu armazenamento privado separado de `br.com.bloco.servidor`, `br.com.bloco.servidor.teste` e `br.com.bloco.servidor.teste204`. Os ZIPs anteriores em `Zips/` são preservados.

## O que o aplicativo faz

- Interface nativa com as abas Painel, Mundo, Console e Ajustes; controle de início/parada, sinalização local, endereço e contagem de jogadores obtida pelo console.
- Ambiente mínimo baseado no Ubuntu Base 24.04 ARM64, PRoot e Box64, instalado em armazenamento privado. O servidor oficial Linux x86-64 é baixado da Mojang após o aceite dos termos e executado no celular; o pacote não redistribui os binários da Mojang.
- Instalação separada do runtime OpenJDK 21 ARM64 (21.0.8, jlink do projeto jdk4py), com tamanho e SHA-256 fixados, extração limitada, substituição recuperável e verificação real de `java -version` no Ubuntu. O Java não é usado pelo Bedrock, não inclui `javac` e não instala um servidor Java Edition. Se a instalação ou a execução do Java falhar, isso é informado e não impede a tentativa de iniciar o Bedrock.
- Importação e exportação de `.mcworld`, cópia anterior ao substituir o mundo, restauração da última cópia e backup antes da atualização do Bedrock. Importação de `.mcpack`/`.mcaddon`, listagem e desativação de conteúdos ativos sem apagar os arquivos. Operações de mundo só ocorrem com o servidor parado.
- Histórico local limitado e rotacionado, cópia/exportação do histórico recente e envio de comandos Bedrock de uma linha diretamente ao processo, sem shell.
- Configuração de nome, de 1 a 5 vagas (5 por padrão), porta, descoberta LAN, autenticação, modo, dificuldade, visão, simulação e limite de threads. `max-threads=0` deixa o Bedrock selecionar as threads disponíveis; não há overclock.

O Ubuntu aqui é um **userspace mínimo**, não um sistema operacional completo nem uma VM com kernel próprio ou desktop. Não possui gerenciador de pacotes/terminal no painel. O app tenta um perfil direto e, se ele não iniciar, tenta o perfil compatível via PRoot. Android, SELinux e políticas do fabricante podem impedir a execução; Box64 traduz o servidor Linux x86-64 para ARM64 e PRoot tem custo. O servidor fica neste celular, mas não é um binário Bedrock ARM64 executado de forma totalmente nativa. O limite configurado é cinco jogadores; não há garantia de desempenho, entrada de jogadores, 24 horas de operação ou de que cinco pessoas funcionarão bem em qualquer aparelho/rede.

## Primeira etapa: jogar pela rede local

1. Instale o Bloco Local em um Android ARM64 e conecte o celular anfitrião e os jogadores à mesma rede Wi-Fi. Redes de convidados ou com isolamento de dispositivos podem bloquear a partida.
2. No Painel, toque em **Ligar servidor** e aceite os termos oficiais. Na primeira preparação, o aplicativo precisa de internet para baixar o servidor Bedrock da Mojang; depois, espere o estado indicar que está ativo.
3. Os amigos precisam usar **Minecraft Bedrock** compatível com a versão indicada no painel — este projeto não hospeda Java Edition. Procure o mundo em Jogar/Amigos ou Servidores; se não aparecer, adicione um servidor usando o IP local e a porta que o aplicativo mostra (19132 por padrão).
4. A configuração permite de 1 a 5 jogadores simultâneos. Comece com poucos jogadores e teste o desempenho do aparelho. Mantenha a descoberta LAN ativada.
5. Nesta etapa não encaminhe portas no roteador nem exponha o servidor à internet. A autenticação fica desligada por padrão para compatibilidade; em rede sem autenticação, nomes podem ser falsificados, então use somente uma rede confiável.

## Requisitos e segurança

- Android 8.0+ com sistema ARM64 de 64 bits, espaço livre para ambiente, Java, servidor, mundos e backups; recomenda-se pelo menos 3 GB livres e, para tentar cinco jogadores, 8 GB de RAM ou mais. Requisitos reais variam.
- Primeira instalação exige internet. O Java é baixado de `files.pythonhosted.org` como wheel `jdk4py` fixado, é verificado antes da extração e não exige Python no aparelho. A versão do Java é fixa e **não recebe atualizações automáticas de segurança**. O Bedrock é consultado na API oficial da Microsoft e baixado da Mojang.
- A autenticação dos jogadores fica desligada por padrão, com confirmação explícita antes de iniciar. Nesse modo nomes são falsificáveis: use somente em rede confiável, sem encaminhamento à internet. A autenticação Microsoft é opcional e pode não funcionar; não houve teste de login de jogador com conta real.
- O Bedrock atual usa TCP para sinalização e UDP para jogo na porta configurada (19132 por padrão). A resposta TCP local não comprova que jogadores conseguem entrar. Acesso externo depende também de IP público, NAT/roteador e, eventualmente, anúncio do endereço UDP; o aplicativo não fornece IP nem túnel.
- O APK é assinado por padrão com chave temporária de desenvolvimento e usa target SDK 28 para tentar executar os componentes no armazenamento privado. O Android pode alertar sobre target antigo. Outra assinatura não atualiza a instalação; **desinstalar apaga seus dados**. Exporte mundos para fora do aplicativo antes de remover qualquer edição.
- Proteja logs exportados: podem incluir nomes de jogadores e outros dados. O histórico permanece local e é limitado a dois arquivos de até aproximadamente 1 MiB; o painel exporta até 256 KiB recentes.

## Testes e limites de cobertura

`scripts/test-mobile-java.sh` cobre limites e segurança das importações, cópias, conteúdos, filtros, configuração, histórico, comandos e a instalação/recuperação do Java. Com `BLOCO_JAVA_WHEEL` apontando para o wheel ARM64 fixado, valida também a extração do pacote real. Em Linux ARM64, passando `runtime.zip` como argumento, o teste instala e executa Java no Ubuntu, executa um programa Java real sob filtros simulados e baixa/inicia o Bedrock oficial.

A CI exercita o Bedrock em perfis direto e PRoot, inclusive com netlink negado e chamadas filtradas simuladas. Um cliente offline de testes entra no servidor, percorre ICE/UDP e chega ao spawn em um namespace de rede separado. Também verifica o APK exato do ZIP em Android 13/15 ARM64 **em contêiner com SELinux desligado**: navegação, consentimento, início e parada pelo painel, comando digitado no console, instalação e execução do Java, reinício e coexistência das três versões anteriores com dados. Esses testes não equivalem ao Redmi físico, a uma rede Wi-Fi real, a login Microsoft ou ao uso com cinco jogadores; filtros simulados não reproduzem todas as políticas OEM.

Em testes anteriores, Box64 registrou SIGSEGV/SIGBUS durante a limpeza após o comando de parada e terminou com código zero. Isso não prova perda nem integridade do mundo: não tratamos o código zero como prova de parada sem falhas e recomendamos cópias exportadas.

## Compilar e testar

As etapas de build do APK usam Java 21, Android SDK Platform 34 e Build Tools 34.0.0. O runtime é compilado em Linux ARM64, e sua compilação baixa e verifica fontes/binários de terceiros. O Bedrock de teste é obtido diretamente da Mojang e não é incorporado ao artefato.

```bash
npm ci --no-audit --no-fund
npm test
npm run build
scripts/test-mobile-java.sh

# Em Linux ARM64; requer sudo para dependências de compilação:
scripts/build-mobile-runtime.sh .mobile-runtime
npm ci --prefix mobile/nethernet-ci --ignore-scripts --no-audit --no-fund
scripts/test-mobile-java.sh .mobile-runtime/runtime.zip

mkdir -p mobile/android/app/src/main/assets
cp .mobile-runtime/runtime.zip mobile/android/app/src/main/assets/runtime.zip
npm run build:android
npm run package:release
unzip -t Zips/bloco-3.0.1-android-celular.zip
```

Para testar a entrada com outro endereço no Linux CI, configure um namespace de rede e as variáveis `BLOCO_NETHER_TEST_NAMESPACE`, `BLOCO_NETHER_TEST_HOST` e `BLOCO_NODE_BIN`, como em `.github/workflows/build.yml`. A pasta `mobile/nethernet-ci` e seu cliente offline são apenas para testes; não vão no APK. Os filtros em `mobile/test/seccomp-*.py` simulam apenas regras escolhidas, não uma política Android completa.

A chave pode ser fornecida via `BLOCO_KEYSTORE`, `BLOCO_KEY_ALIAS`, `BLOCO_KEYSTORE_PASSWORD` e `BLOCO_KEY_PASSWORD`; não a versionar. Sem uma chave, o build gera uma chave de desenvolvimento temporária. O empacotador não sobrescreve ZIPs existentes e inclui instruções, licenças e fontes dos componentes distribuídos. O projeto não cria tags ou releases automaticamente.

Os diretórios `server/`, `src/`, `android/` e o painel web permanecem como implementação legada; não são serviço de hospedagem exigido pelo aplicativo Android atual. Minecraft é marca de seus proprietários; Bloco Local é independente e não afiliado à Mojang/Microsoft.
