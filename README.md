# Bloco

Um painel instalável no celular para administrar **um servidor Minecraft Bedrock** (compatível com a edição Bedrock para Windows 10/11 e outros dispositivos Bedrock). A interface é em português e foi pensada para funcionar bem em telas pequenas.

> **Importante:** o Bloco é o painel, não uma empresa de hospedagem. Para manter o jogo no ar 24 horas, é preciso uma máquina ou hospedagem externa que fique ligada e conectada à internet. Hospedagem pode ter custo, e a disponibilidade depende do provedor. Fechar o aplicativo no celular não desliga o servidor.

É possível fazer a instalação e usar o painel pelo celular, por exemplo usando o terminal no navegador oferecido por um provedor de nuvem. A instalação inicial, porém, **exige configurar uma hospedagem Linux com Docker, rede e HTTPS**; não existe provisionamento automático de nuvem; o APK precisa se conectar a um painel já hospedado.

## O que o painel faz

- Cria e controla um servidor Bedrock com **10 vagas** e autenticação online do Minecraft.
- Usa `VERSION=LATEST` na imagem [itzg/minecraft-bedrock-server](https://github.com/itzg/docker-minecraft-bedrock-server): a versão estável mais recente disponível é verificada ao **iniciar/reiniciar** o servidor. Não há atualização enquanto ele permanece ligado; use **Ver detalhes → Verificar atualização** para reiniciá-lo.
- Mostra estado real do contêiner e, quando a consulta ao servidor está disponível, versão e quantidade de jogadores. Não inventa valores quando a hospedagem está desconectada.
- Importa mundos `.mcworld` e `.zip`; substituições mantêm uma cópia do mundo anterior na hospedagem.
- Importa add-ons e texturas `.mcpack`, `.mcaddon` e `.zip`, lê os manifestos e ativa os pacotes de comportamento e recursos no mundo.
- Baixa o mundo em `.mcworld`, incluindo os pacotes ativos encontrados pelo painel.
- Pode ser instalado na tela inicial como **PWA**, sem precisar publicar o app em uma loja.

O servidor precisa estar **parado** para importar, desativar conteúdos ou baixar o mundo. Os formatos aceitos são arquivos ZIP Bedrock de até 512 MB, com até 1 GB após a extração. Conteúdos protegidos do Marketplace, arquivos criptografados e alguns add-ons que dependem de recursos experimentais/licenças próprias podem não funcionar. Jogadores podem precisar aceitar o download das texturas ao entrar. Antes de substituir um mundo importante, faça também um backup externo.

O servidor é público por padrão: qualquer pessoa que conheça o endereço pode tentar entrar, usando uma conta válida do Minecraft. O painel não configura lista de convidados. Cada jogador precisa de uma versão compatível do Minecraft Bedrock e de uma conta Microsoft. Minecraft Java Edition não é compatível com este servidor.

## Pré-requisitos para um servidor 24h

1. Uma máquina Linux (preferencialmente x86_64) com Docker Engine e o plugin Docker Compose, armazenamento persistente e acesso contínuo à internet. Recomendação inicial: pelo menos 2 vCPUs e 4 GB de RAM; 10 jogadores não são uma garantia de desempenho e mundos/add-ons maiores podem exigir mais recursos.
2. Um **IPv4 público** acessível pelos jogadores (ou um IPv4 da rede local para uso apenas em LAN). Use um IP estável; atrás de CGNAT ou sem encaminhamento de portas, jogadores externos não conseguirão entrar.
3. Permitir no firewall da hospedagem e da rede **TCP 19132** e **UDP 19140–19155**, necessários para o transporte NetherNet atual. Encaminhe todas essas portas se houver roteador/NAT. A porta de entrada mostrada aos jogadores é **19132**.
4. Um domínio com HTTPS, ou outro proxy HTTPS confiável, para abrir o painel com segurança e instalá-lo no celular. O PWA só é instalável em um contexto seguro (HTTPS ou localhost). Certificados, DNS e hospedagem não são fornecidos pelo Bloco.

## Instalação na hospedagem

Coloque os arquivos deste projeto na máquina e, no terminal dentro da pasta do projeto:

```sh
cp .env.example .env
# Edite .env: defina ADMIN_TOKEN com uma chave exclusiva e forte de 16+ caracteres.
# Opcionalmente, defina PUBLIC_IPV4 com o IPv4 público da máquina.
docker compose up -d --build
```

Uma chave aleatória pode ser gerada com `openssl rand -hex 32`. Guarde-a com segurança: ela será solicitada ao abrir o painel. Não use a mesma chave em outros serviços. O arquivo `.env` não deve ser publicado.

Por segurança, o Compose publica o painel somente em `127.0.0.1:3000` na máquina de hospedagem. Configure um proxy reverso HTTPS para esse endereço. Por exemplo, com o Caddy instalado no **host**, depois de apontar o DNS do domínio para o servidor e liberar TCP 80/443:

```caddyfile
painel.seu-dominio.com {
    reverse_proxy 127.0.0.1:3000
}
```

Se houver exatamente um proxy reverso confiável como esse, defina `TRUST_PROXY=1` no `.env` e execute `docker compose up -d` novamente. Não exponha a porta 3000 em HTTP na internet: a chave de acesso seria transmitida sem criptografia. Restrinja o acesso ao painel a pessoas confiáveis.

Abra o endereço HTTPS no celular e informe a chave. No Android, use **Instalar aplicativo** / **Adicionar à tela inicial** no navegador; no iPhone, use **Compartilhar → Adicionar à Tela de Início**. No painel:

1. Toque em **Criar meu servidor**, informe nome e IPv4 da hospedagem, aceite os termos do Minecraft e aguarde a primeira inicialização.
2. No Minecraft Bedrock, acesse **Servidores → Adicionar servidor** e informe o IPv4 e a porta `19132`. Compartilhe esses dados com seus amigos.
3. Para importar um mundo, pare o servidor, toque em **Importar mundo**, escolha o arquivo e inicie o servidor novamente. Um mundo também pode ser importado antes de criar o servidor.
4. Para add-ons/texturas, primeiro crie ou importe um mundo. Pare o servidor, toque em **Adicionar conteúdo** e depois inicie-o para aplicar os pacotes.

O contêiner do jogo usa política de reinício `unless-stopped` e os dados ficam no volume Docker `bloco-bedrock-data`; a configuração do painel fica em `bloco-panel-data`. Esses volumes continuam existindo após reiniciar o painel ou a máquina. **Não remova os volumes sem fazer backup.** As cópias automáticas dos mundos substituídos ficam em `bloco-backups` dentro do volume do jogo. Quando o servidor estiver parado, o botão **Baixar** permite guardar o mundo atual fora da hospedagem.

### Segurança da hospedagem

O painel controla o Docker por `/var/run/docker.sock`. Acesso ao Docker equivale, na prática, a acesso de administrador à máquina. Use uma hospedagem dedicada, mantenha sistema/imagens atualizados, proteja a chave, limite quem acessa o painel e use HTTPS. O token é guardado apenas na sessão do navegador. Não há isolamento entre múltiplos usuários nem múltiplos servidores neste projeto.

## Desenvolvimento e testes

Requer Node.js 22+.

```sh
npm ci
npm run dev       # interface em http://localhost:5173 e API em localhost:3000
npm test
npm run build
npm start         # serve a versão compilada na porta 3000
```

Sem `ADMIN_TOKEN` de 16+ caracteres, o painel roda em **modo de apresentação somente leitura**. Sem Docker disponível, mostra que a hospedagem não está conectada. Para um teste real em uma máquina com Docker, configure `ADMIN_TOKEN` no ambiente; executando fora do Compose, os arquivos do jogo ficam no diretório local `data/bedrock`, montado no contêiner. Em produção, prefira o Compose e os volumes descritos acima.

Os testes automatizados simulam o Docker e verificam configurações, autenticação, importação/exportação e segurança de arquivos. Eles não substituem um teste de conexão com um servidor Bedrock real, firewall e clientes Minecraft.

---

Projeto independente, não afiliado à Mojang ou Microsoft. Minecraft e Bedrock são marcas de seus respectivos proprietários. O uso do servidor exige aceitar os [termos do Minecraft](https://www.minecraft.net/pt-br/eula).

## APK Android e ZIP por versão

Além do PWA, o projeto inclui um aplicativo Android 7.0+ que se conecta ao painel por **HTTPS**. Ele não hospeda o servidor no celular: é preciso instalar o painel em uma hospedagem conforme as instruções acima. O APK oferece seletor de arquivos para importar mundos e conteúdos e permite salvar o mundo pelo seletor do Android.

Para compilar uma versão, instale Java 21, Android SDK Platform 34 e Build Tools 34.0.0 e configure `ANDROID_HOME`:

```sh
npm ci
npm test
npm run build
npm run build:android
npm run package:release
```

O comando gera `Zips/bloco-<versão>-android-servidor.zip`, contendo o APK, o painel compilado, os arquivos necessários para implantá-lo e instruções. O empacotador não sobrescreve ZIPs existentes. Por padrão, o APK é assinado com uma chave temporária de desenvolvimento; versões geradas com outra chave podem exigir desinstalar o APK anterior. Para uma distribuição com assinatura estável, forneça externamente `BLOCO_KEYSTORE`, `BLOCO_KEY_ALIAS`, `BLOCO_KEYSTORE_PASSWORD` e `BLOCO_KEY_PASSWORD` ao build, sem colocá-los no repositório ou no ZIP.

O workflow em `.github/workflows/build.yml` também compila e publica o ZIP como artefato temporário do GitHub Actions (o download pode ser um ZIP externo com o pacote dentro).
