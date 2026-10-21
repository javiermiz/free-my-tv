# Como instalar o Free My TV · versão streaming

[English](SETUP.md) · [Español](SETUP.es.md) · **Português**

Esta é uma versão do [Free My TV](https://github.com/simbasjungles/free-my-tv) (de simbasjungles, licença MIT) com uma
tela inicial no estilo dos apps de streaming. Aqui estão **todos** os passos, do zero até ter na sua TV. Na primeira vez
leva uns 20 minutos, quase tudo esperando downloads.

Resumo:

1. [Instale as ferramentas no computador](#1-as-ferramentas)
2. [Prepare a TV](#2-prepare-a-tv)
3. [Consiga as chaves](#3-as-chaves) (TMDB obrigatória; Stremio e YouTube opcionais)
4. [Preencha o `config.properties`](#4-preencha-o-configproperties)
5. [Compile e instale](#5-compile-e-instale)
6. [Ative na TV](#6-ative-na-tv)
7. [Atualizar, voltar ao original e problemas comuns](#7-atualizar-voltar-ao-original-e-problemas)

> **Suas chaves são suas e secretas.** Elas vão só no `config.properties`, que nunca vai para o git (está no
> `.gitignore`). Não cole em issues, chats nem capturas de tela.

---

## 1. As ferramentas

No computador você precisa de:

- **git**, para baixar o código.
- **Um JDK 17 ou mais novo** (Java), para compilar.
- **As ferramentas de linha de comando do Android SDK**: `build-tools` 34 ou mais novo e a plataforma `android-34`.
- **adb**, para instalar o app na TV pelo Wi-Fi.
- **Python 3**, só se você for pegar a chave do Stremio com e-mail e senha.

### macOS

1. Se você não tem o [Homebrew](https://brew.sh), abra o app *Terminal* e cole a linha de instalação da página dele. No
   final, rode as duas linhas que ele mostra em "Next steps" para colocá-lo no PATH.
2. Instale o Java, o SDK e o adb:
   ```bash
   brew install --cask temurin android-commandlinetools android-platform-tools
   ```
3. Baixe o build-tools e a plataforma do Android (aceite as licenças com `y` quando perguntar):
   ```bash
   sdkmanager "build-tools;34.0.0" "platforms;android-34"
   ```
4. Confira se está tudo lá:
   ```bash
   java -version
   ```
   ```bash
   adb version
   ```
   `java -version` precisa dizer 17 ou mais. Se disser outra versão (ou você tiver várias), compile com a 17 usando
   `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./build.sh`.

`git` e `python3` já vêm no macOS: na primeira vez que usar, ele pode pedir para instalar as *Command Line Tools*;
aceite.

### Linux

1. Instale git, OpenJDK 17, adb e Python 3 pelo gerenciador de pacotes. No Ubuntu ou Debian:
   ```bash
   sudo apt install git openjdk-17-jdk adb python3 unzip curl
   ```
2. Baixe as *Command line tools only* em [developer.android.com/studio](https://developer.android.com/studio#command-tools),
   descompacte em `~/Android/Sdk/cmdline-tools/latest/` e rode:
   ```bash
   ~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager "build-tools;34.0.0" "platforms;android-34"
   ```
   O `build.sh` encontra o SDK sozinho em `~/Android/Sdk`. Se você colocou em outro lugar, exporte `ANDROID_HOME` com
   esse caminho.

### Windows

Instale o [WSL](https://learn.microsoft.com/pt-br/windows/wsl/install) (Ubuntu) e siga os passos do Linux dentro dele.

### Baixe o código

```bash
git clone https://github.com/javiermiz/free-my-tv
```
```bash
cd free-my-tv
```

---

## 2. Prepare a TV

1. **Ative o modo desenvolvedor:** Configurações → Sistema → Sobre → desça até *Versão do SO do Android TV* e aperte OK
   **7 vezes** seguidas, até aparecer que você é desenvolvedor. (Em algumas TVs fica em Configurações → Preferências do
   dispositivo → Sobre.)
2. **Ative a depuração:** Configurações → Sistema → Opções do desenvolvedor → ative **Depuração USB**. Se a sua TV tiver
   também *Depuração pela rede* ou *Depuração sem fio*, ative também.
3. **Anote o IP da TV:** Configurações → Rede e Internet → sua rede Wi-Fi (ou Ethernet) → *Endereço IP*. Algo como
   `192.168.1.50`. O computador e a TV precisam estar na mesma rede.
4. **Instale os apps que você quer abrir pela tela inicial** pela Play Store da TV: Netflix, Disney+, Prime Video,
   Paramount+, Crunchyroll e **Stremio**. Os que você não tiver simplesmente não aparecem.
5. **Se for usar o Stremio**, abra o Stremio na TV e entre com **a mesma conta** de onde vai tirar a chave (passo 3).

---

## 3. As chaves

| Chave | Obrigatória? | O que acontece sem ela |
|---|---|---|
| TMDB (`tmdb_key`) | **Sim** | A tela inicial fica sem títulos, imagens e trailers. |
| Stremio (`stremio_key`) | Não | Sem Continuar assistindo, Minha lista, Novos episódios, sugestões pelo que você assiste e player. |
| YouTube (`youtube_key`) | Não | Quando o TMDB não tem trailer no seu idioma, usa o que o TMDB tiver (um clipe, a abertura de um anime ou o trailer original sem som). |

### TMDB (`tmdb_key`): obrigatória, grátis

O TMDB é o banco de dados de filmes e séries de onde vêm as sugestões, os pôsteres, os logos, os trailers e em qual
serviço está cada título.

1. Crie uma conta em [themoviedb.org/signup](https://www.themoviedb.org/signup) e confirme o seu e-mail (chega um link).
2. Entre e vá para [themoviedb.org/settings/api](https://www.themoviedb.org/settings/api) (seu avatar → Configurações →
   API).
3. Clique em **Criar** (ou *Request an API Key*) e escolha **Developer**.
4. Aceite os termos e preencha o formulário: tipo de uso *Pessoal*, nome do app (por exemplo "Free My TV"), URL (pode
   ser `https://github.com/javiermiz/free-my-tv`) e um resumo curto ("Tela inicial para a minha TV"). O TMDB pede seus
   dados de contato; eles não são compartilhados com mais ninguém.
5. Ao enviar, você verá dois valores: **API Key** (32 caracteres) e **API Read Access Token** (bem longo, começa com
   `eyJ`). Qualquer um dos dois serve; copie um.

### Stremio (`stremio_key`): opcional

É a chave da sua sessão do Stremio. Com ela, a tela inicial lê a sua biblioteca (Continuar assistindo, Minha lista,
Novos episódios), sugere títulos parecidos com os últimos que você viu, e o player salva o seu ponto na sua conta (o que
você assiste na TV aparece igual no Stremio e no celular). Não é a sua senha: é uma chave de sessão. Para invalidá-la um
dia, saia do Stremio em todos os seus dispositivos.

Se ainda não tem conta, crie grátis em [stremio.com](https://www.stremio.com).

**Se você entra no Stremio com e-mail e senha** (o mais fácil):

1. Rode `./build.sh` uma vez antes (passo 5) para que o `config.properties` exista. Não importa se a compilação dá certo
   ou não: o que importa é que ele crie o arquivo.
2. Na pasta do projeto:
   ```bash
   python3 tools/stremio_key.py
   ```
3. Digite o seu e-mail e a sua senha do Stremio (a senha não aparece enquanto você digita). Eles vão só para
   `api.strem.io`, e nunca são salvos nem mostrados. A chave fica gravada sozinha no `config.properties`.

**Se você entra com Facebook ou Google** (não tem senha do Stremio):

1. No computador, abra [web.stremio.com](https://web.stremio.com) no Chrome, Edge ou Firefox e entre como sempre.
2. Abra o console do navegador: **F12** (no Mac, **Cmd+Option+J** no Chrome ou **Cmd+Option+K** no Firefox) → aba
   *Console*. Se o navegador pedir para digitar `allow pasting` antes de colar, digite e aperte Enter.
3. Cole isto e aperte Enter:
   ```js
   (() => { for (const k of Object.keys(localStorage)) { try { const o = JSON.parse(localStorage.getItem(k)); const key = o?.auth?.key || o?.profile?.auth?.key; if (key) { copy(key); return "Copiada (de " + k + ")"; } } catch (e) {} } return "Não encontrada"; })()
   ```
4. Se disser *Copiada*, a chave está na sua área de transferência: cole depois de `stremio_key=` no
   `config.properties`. Se disser *Não encontrada*, recarregue a página com a sessão aberta e tente de novo.

**Para o player** você também precisa, na sua conta do Stremio, de add-ons que deem **links diretos** (HTTP). O player
usa os add-ons que você já tem instalados na conta e escolhe o melhor stream: áudio em espanhol latino, depois o original
com legendas em espanhol, depois espanhol da Espanha, depois o resto. Torrents não tocam aqui (precisam do motor do
Stremio): se um título só tem torrents, a tela inicial abre no app do Stremio. Use só add-ons e fontes que você tem
direito de assistir.

### YouTube (`youtube_key`): opcional, grátis

Serve para buscar no YouTube o trailer quando o TMDB não tem: em espanhol latino (ou legendado em espanhol) com
`language=es`, e o trailer oficial nos outros idiomas.

1. Entre em [console.cloud.google.com](https://console.cloud.google.com) com a sua conta do Google. Se for a primeira
   vez, aceite os termos (não pede cartão).
2. No topo, no seletor de projetos → **Novo projeto** → dê um nome (por exemplo "free-my-tv") → **Criar**, e
   selecione-o.
3. Menu ☰ → **APIs e serviços** → **Biblioteca** → procure **YouTube Data API v3** → abra → **Ativar**.
4. Menu ☰ → **APIs e serviços** → **Credenciais** → **Criar credenciais** → **Chave de API**. Copie a chave (começa com
   `AIza`).
5. Recomendado: nessa chave clique em **Editar chave de API** → *Restrições de API* → **Restringir chave** → marque só
   *YouTube Data API v3* → **Salvar**. Assim, se alguém vir a chave, ela não serve para mais nada.

É grátis: dá para umas 100 buscas de trailer por dia. Quando acabam, a tela inicial usa o que o TMDB tiver e busca de
novo no dia seguinte. Os trailers encontrados ficam salvos na TV, então cada título só é buscado uma vez.

---

## 4. Preencha o `config.properties`

Se ainda não existe, rode `./build.sh` uma vez: ele cria o arquivo copiando o `config.example.properties`. Abra em
qualquer editor de texto (no Mac: `open -e config.properties`) e preencha:

| Chave | O que colocar | Exemplo |
|---|---|---|
| `tmdb_key` | Sua chave do TMDB (passo 3) | `eyJhbGciOi…` |
| `tmdb_region` | Seu país em duas letras (decide qual título está em qual serviço) | `BR`, `PT`, `US` |
| `latitude`, `longitude` | As coordenadas da sua cidade, em graus decimais | São Paulo: `-23.5505`, `-46.6333` |
| `city` | O nome que aparece ao lado do clima | `São Paulo` |
| `language` | `pt` para títulos e sinopses do TMDB em português; `es` para espanhol; `en` para inglês | `pt` |
| `temperature` | `C` ou `F` | `C` |
| `stremio_key` | Sua chave do Stremio (passo 3), ou vazio | |
| `youtube_key` | Sua chave do YouTube (passo 3), ou vazio | `AIza…` |
| `disable_assistant` | `true` também desliga o Google Assistente (o botão de microfone do controle para de funcionar) | `false` |

**Para pegar as coordenadas:** abra o [Google Maps](https://maps.google.com), clique com o botão direito na sua cidade e
clique na primeira linha (os dois números): eles são copiados. O primeiro é `latitude`, o segundo `longitude`. O centro
da cidade basta; não use a sua casa.

Escreva cada valor logo depois do `=`, sem aspas nem espaços. Por exemplo:

```properties
tmdb_key=eyJhbGciOi...
tmdb_region=BR
latitude=-23.5505
longitude=-46.6333
city=São Paulo
language=pt
temperature=C
```

---

## 5. Compile e instale

1. **Compile** na pasta do projeto:
   ```bash
   ./build.sh
   ```
   Na primeira vez ele baixa o player (ExoPlayer) para `libs/` e cria a sua chave de assinatura `freemytv.keystore`.
   Tem que terminar com `Built FreeMyTV.apk`. Se não encontrar o JDK, use
   `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./build.sh` (Mac) ou instale o JDK 17 (Linux).
2. **Conecte na TV** (troque pelo IP da sua TV):
   ```bash
   adb connect 192.168.1.50:5555
   ```
   A TV mostra *Permitir depuração USB?*: marque **Sempre permitir neste computador** e aperte **OK**. Confira com
   `adb devices`: tem que dizer `device` (se disser `unauthorized`, aceite o aviso na TV).
3. **Instale:**
   ```bash
   adb install -r FreeMyTV.apk
   ```
   Tem que dizer `Success`.

**Guarde o `freemytv.keystore`** (no seu gerenciador de senhas ou num backup, por exemplo): as atualizações precisam ser
assinadas com a mesma chave. Se você perder, vai ter que desinstalar o app e instalar de novo. Ele também não vai para o
git.

---

## 6. Ative na TV

1. Na TV, abra o app **Free My TV** (está nos seus apps).
2. Entre em **Início** e escolha **Free My TV**.
3. A TV pergunta de novo *Permitir depuração USB?*: é o app usando o ADB da própria TV para fazer a troca. Marque
   **Sempre permitir** e aceite.
4. Aperte o botão de início do controle: você está na tela inicial nova.

A primeira carga de sugestões leva uns 3 minutos e se repete a cada 6 horas, em segundo plano. Para ver o que a troca
fez: `adb logcat -s FreeMyTV`.

Não troque o modo pelo computador: faça sempre pelo app na TV.

---

## 7. Atualizar, voltar ao original e problemas

**Atualizar** (você mudou o `config.properties` ou baixou código novo com `git pull`): repita o passo 5 (`./build.sh`,
`adb connect`, `adb install -r FreeMyTV.apk`). Não precisa ativar de novo.

**Voltar ao original:** na TV, *Free My TV* → *Início* → *Original*. A tela inicial de fábrica, o protetor de tela e
todos os apps voltam exatamente como estavam. Para remover de vez, troque para *Original* primeiro e depois desinstale o
*Free My TV* em Configurações → Apps. Se a tela inicial nova falhar três vezes em cinco minutos, ela volta sozinha.

| Problema | O que fazer |
|---|---|
| `adb connect` diz `failed to connect` ou `timed out` | Confira o IP e se a TV e o computador estão na mesma rede. Confira se a *Depuração USB* (e a *pela rede*, se existir) continua ativa. Desligue e ligue a TV e tente de novo. |
| `adb devices` diz `unauthorized` | Aceite o aviso na TV. Se não aparecer: `adb disconnect`, depois `adb connect` de novo. |
| `adb install` diz `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | O app instalado foi assinado com outra chave (você perdeu o `freemytv.keystore`). Troque para *Original*, desinstale o Free My TV e instale de novo. |
| `Android SDK not found` | Instale o SDK (passo 1) ou exporte `ANDROID_HOME` com o caminho dele. |
| `Android platform not found` | Rode `sdkmanager "platforms;android-34"`. |
| `JDK not found` | Instale o JDK 17 ou use `JAVA_HOME=…` (passo 5). |
| `latitude must be a number` | No `config.properties`, use ponto decimal e sem aspas: `-23.5505`. |
| A tela inicial aparece vazia ou diz que falta a chave do TMDB | Confira a `tmdb_key`, compile e instale de novo. Espere uns 3 minutos pela primeira carga. |
| Não aparece Continuar assistindo | Confira a `stremio_key` e se você tem algo começado no Stremio. Se mudou a senha do Stremio ou saiu de todos os lugares, pegue a chave de novo. |
| Um título do Stremio não toca no player | Esse título não tem links diretos nos seus add-ons; abra no app do Stremio. Streams 4K e Dolby Vision são tentados por último porque algumas TVs não decodificam. |
| Os trailers aparecem sem som ou em outro idioma | O TMDB não tem um no seu idioma. Adicione a `youtube_key`; se já tem, talvez as buscas do dia tenham acabado. |
| O botão da Netflix (ou outro atalho) do controle não faz nada | Em algumas TVs (TCL) esses atalhos pertencem à tela inicial de fábrica; descansam enquanto o Free My TV está ativo e voltam com *Original*. |

## Notas

- **TVs Hisense** (e outras sem `cmd role get-role-holders`): já está resolvido; a troca encontra a tela inicial de
  fábrica de outro jeito.
- **Nunca envie** `config.properties`, `freemytv.keystore` nem nenhuma chave. O `.gitignore` já deixa de fora.
- Falta algo aqui? Abra uma issue neste repositório (sem colar as suas chaves).
