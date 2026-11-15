# REA #212 — linguagem visual dos cards expansíveis (Android)

Situação: implementação inicial; falta validação em dispositivo real. Referência aprovada pelo usuário: popup de filtro/ordenação na Biblioteca (15/11/2026).

## Contratos

| Papel | Fundo | Blur | Interação |
| --- | --- | --- | --- |
| Filtro / ordenação | neutro #29292D no escuro, #F5F5F7 no claro | Haze do mesmo host quando disponível; fallback sólido | ancorado ao botão, sem deslocar chrome |
| Avaliação | mesmo neutro; capa apenas desfocada e suavizada | Haze ou capa difusa, nunca superfície sem fundo | preservar a âncora e ações |
| Seleção múltipla | mesma placa neutra **totalmente opaca** | **sem Haze** da lista interativa | dock fechado por padrão, menu cresce para cima, lista de ações rolável |
| Pressionamento longo | mesma placa neutra **totalmente opaca** | **sem blur** no card, fundo escurecido sutil | manter item elevado e menu sincronizados |

O tom da capa deve dar personalidade à página, mas não colorir arbitrariamente as superfícies de controles. Evitar menus inteiros verdes/roxos e qualquer texto de lista visível através do card de ações.

## Aceite antes de encerrar

1. Selecionar artista: dock compacto não encobre os nomes, ações expandem acima e continuam acessíveis; recolher e limpar seleção.
2. Testar também seleção de faixas e álbuns em telas pequenas e com miniplayer presente.
3. Pressionamento longo em páginas escuras e dinâmicas: cor e contraste estáveis, sem interferência do fundo ou duplicação de texto/capa.
4. Filtros e avaliação preservam a estética frosted; modo reduzir blur apresenta fallback legível.
5. Abrir e fechar repetidamente sem piscadas, sem alterar a navegação ou o vetor do ⋯ do player.

Essa regra é comum à família de cards, mas cada papel conserva sua semântica: blur nos filtros e avaliação; solidez na seleção e no pressionamento longo.
