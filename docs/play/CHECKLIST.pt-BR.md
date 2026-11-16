# Google Play: o que falta, e quem faz

## Pronto no repositório
- AAB assinado: `./gradlew :app:bundleRelease` com `RELEASE_KEYSTORE` e `RELEASE_KEYSTORE_PASS` (a chave em
  `~/.android-keys/fm1-emulator-release.jks` vira a **chave de upload**; a Play guarda a chave de assinatura do app).
- Id do app: `io.github.orianbsilva.fm1pocket` (permanente depois do primeiro upload). Nome: **Pocket Synth Emulator**.
- Alvo: Android 16 (API 36), mínimo Android 8 (API 26); 64 bits (arm64-v8a, x86_64); bibliotecas nativas alinhadas em
  16 KB (exigência da Play).
- Textos da loja: `listing-en-US.md`, `listing-pt-BR.md` (dentro dos limites de 30 / 80 / 4000 / 500).
- Imagens: `graphics/` (ícone 512×512, destaque 1024×500, 5 screenshots deitadas 2160×1080, 2 em pé 1080×2160).
- Política de privacidade: https://orianbsilva.github.io/fm1-pocket/privacy.html
- Respostas: `data-safety.md` (Segurança dos dados), `app-content.md` (classificação, público, notas para a revisão).

## Só você pode fazer
1. **Conta de desenvolvedor** no Play Console (US$ 25, uma vez), com verificação de identidade (documento) e de
   telefone. Conta pessoal está ok.
2. **Teste fechado obrigatório**: contas pessoais novas precisam de um teste fechado com **pelo menos 12 testadores**
   que aceitem o convite e fiquem **14 dias seguidos** antes de pedir a produção. Dá para convidar por uma lista de
   emails ou um Grupo do Google (amigos, a comunidade do FM-1).
3. Criar o app no Console: nome "Pocket Synth Emulator", idioma padrão en-US (com pt-BR), app gratuito, sem anúncios.
4. Preencher **Conteúdo do app** com `app-content.md` e `data-safety.md`; **Ficha da loja** com os textos e as imagens.
5. Subir o AAB no teste fechado; depois dos 14 dias, pedir a produção (o Google responde às perguntas sobre o teste).

## Riscos que vale saber
- **Marca:** o título não usa "FM-1"; a descrição diz "não oficial" e que M-VAVE e FM-1 são marcas dos donos. Se a
  M-VAVE reclamar, a ficha pode cair até ajustar.
- **Código baixado:** a política proíbe baixar código Android fora da Play; os firmwares são código de outro aparelho
  rodando no emulador (como ROMs em emuladores de console). As notas para a revisão explicam isso.
- **Verificação de desenvolvedor (Android, 2026):** no Brasil, apps instalados fora da Play em aparelhos certificados
  passam a exigir desenvolvedor verificado. A conta do Play Console (passo 1) também registra o nome do pacote; o
  APK do GitHub continua instalável depois disso.
