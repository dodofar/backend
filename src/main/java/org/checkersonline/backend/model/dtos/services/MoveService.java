package org.checkersonline.backend.model.dtos.services;

import org.checkersonline.backend.exceptions.InvalidMoveException;
import org.checkersonline.backend.exceptions.SessionGameNotFoundException;
import org.checkersonline.backend.model.daos.GameDao;
import org.checkersonline.backend.model.dtos.MoveDto;
import org.checkersonline.backend.model.entities.Game;
import org.checkersonline.backend.model.entities.enums.Team;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class MoveService {
    @Autowired
    private GameDao gameDao;

    public Game makeMove(String gameId, MoveDto dto) {
        // Carica partita e valida turno
        Game game = gameDao.findById(gameId)
                .orElseThrow(() -> new SessionGameNotFoundException(gameId));
        if (dto.getPlayer() == null) {
            throw new InvalidMoveException("Player field is required");
        }
        Team player;
        try {
            player = Team.valueOf(dto.getPlayer().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new InvalidMoveException("Invalid player: " + dto.getPlayer());
        }
        if (game.getTurno() != player) {
            throw new InvalidMoveException("Not " + player + "'s turn.");
        }

        String[][] board = game.getBoard();
        int fromR = Character.getNumericValue(dto.getFrom().charAt(0));
        int fromC = Character.getNumericValue(dto.getFrom().charAt(1));
        int toR   = Character.getNumericValue(dto.getTo().charAt(0));
        int toC   = Character.getNumericValue(dto.getTo().charAt(1));
        validateCoordinates(fromR, fromC);
        validateCoordinates(toR, toC);

        String piece = board[fromR][fromC];
        if (piece.isEmpty()) {
            throw new InvalidMoveException("No piece at " + dto.getFrom());
        }
        boolean isKing = Character.isUpperCase(piece.charAt(0));
        boolean isWhite = piece.equalsIgnoreCase("w");
        if ((player == Team.WHITE) != isWhite) {
            throw new InvalidMoveException("Piece does not belong to player.");
        }
        if (!board[toR][toC].isEmpty()) {
            throw new InvalidMoveException("Destination not empty.");
        }

        int dr = toR - fromR;
        int dc = toC - fromC;
        boolean isCapture = Math.abs(dr) == 2 && Math.abs(dc) == 2;

        // Regola presa obbligatoria
        if (!isCapture && hasAnyCaptures(board, player)) {
            throw new InvalidMoveException("Capture available, must capture.");
        }

        // Esegui mossa o cattura
        if (isCapture) {
            int midR = fromR + dr / 2, midC = fromC + dc / 2;
            String capturedPiece = board[midR][midC];

            // Verifica che ci sia un pezzo avversario da catturare
            if (capturedPiece.isEmpty()) {
                throw new InvalidMoveException("No piece to capture");
            }

            boolean capturedIsWhite = capturedPiece.equalsIgnoreCase("w");
            if (isWhite == capturedIsWhite) {
                throw new InvalidMoveException("Cannot capture your own piece");
            }

            // Rimuove il pezzo mangiato
            removePiece(board, midR, midC, game);

            // Sposta il pezzo
            board[fromR][fromC] = "";
            board[toR][toC] = piece;

            // Promozione se dovuta
            if (!isKing && isWhite && toR == 0) {
                piece = "W"; // Promozione a dama bianca
                game.setPedineW(game.getPedineW() - 1);
                game.setDamaW(game.getDamaW() + 1);
                board[toR][toC] = piece;
            }
            if (!isKing && !isWhite && toR == 7) {
                piece = "B"; // Promozione a dama nera
                game.setPedineB(game.getPedineB() - 1);
                game.setDamaB(game.getDamaB() + 1);
                board[toR][toC] = piece;
            }

            // Controllo se può ancora catturare
            if (canStillCapture(board, toR, toC)) {
                // Non cambiare turno
                game.getCronologiaMosse().add(dto.getFrom() + "-" + dto.getTo() + "-" + dto.getPlayer());
                return gameDao.save(game);
            }

            // Cambio turno e fine partita
            Team opponent = (player == Team.WHITE ? Team.BLACK : Team.WHITE);
            game.setTurno(opponent);
            checkEndGame(game);
            game.getCronologiaMosse().add(dto.getFrom() + "-" + dto.getTo() + "-" + dto.getPlayer());
            return gameDao.save(game);
        } else if (isKing) {
            validateKingSimpleMove(board, dr, dc);
        } else {
            validateManSimpleMove(dr, dc, isWhite);
        }

        // Sposta il pezzo e promuovi
        board[fromR][fromC] = "";
        if (!isKing && isWhite && toR == 0) {
            piece = "W"; // Promozione a dama bianca
            game.setPedineW(game.getPedineW() - 1);
            game.setDamaW(game.getDamaW() + 1);
        }
        if (!isKing && !isWhite && toR == 7) {
            piece = "B"; // Promozione a dama nera
            game.setPedineB(game.getPedineB() - 1);
            game.setDamaB(game.getDamaB() + 1);
        }
        board[toR][toC] = piece;

        // Cambio turno e fine partita
        Team opponent = (player == Team.WHITE ? Team.BLACK : Team.WHITE);
        game.setTurno(opponent);
        checkEndGame(game);

        game.getCronologiaMosse().add(""+fromR + fromC + "-" + toR + toC + "-" + dto.getPlayer());
        return gameDao.save(game);
    }

    private boolean canStillCapture(String[][] board, int r, int c) {
        String piece = board[r][c];
        if (piece == null || piece.isEmpty()) return false;

        boolean isKing = Character.isUpperCase(piece.charAt(0));
        boolean isWhite = piece.equalsIgnoreCase("w");

        if (!isKing) {
            int forwardDir = isWhite ? -1 : 1;
            int[][] dirs = {{forwardDir, 1}, {forwardDir, -1}};

            for (int[] d : dirs) {
                int mr = r + d[0], mc = c + d[1];
                int nr = r + 2 * d[0], nc = c + 2 * d[1];
                if (mr < 0 || mr > 7 || mc < 0 || mc > 7 || nr < 0 || nr > 7 || nc < 0 || nc > 7) continue;
                String mid = board[mr][mc];
                String dst = board[nr][nc];
                if (mid.isEmpty() || dst == null || !dst.isEmpty()) continue;
                if (mid.equalsIgnoreCase(piece)) continue;
                boolean isOpponentPiece = mid.equalsIgnoreCase("w") != isWhite;
                if (isOpponentPiece) return true;
            }
        } else {
            int[][] dirs = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
            for (int[] d : dirs) {
                int mr = r + d[0], mc = c + d[1];
                int nr = r + 2 * d[0], nc = c + 2 * d[1];
                if (mr < 0 || mr > 7 || mc < 0 || mc > 7 || nr < 0 || nr > 7 || nc < 0 || nc > 7) continue;
                String mid = board[mr][mc];
                String dst = board[nr][nc];
                if (mid.isEmpty() || dst == null || !dst.isEmpty()) continue;
                if (mid.equalsIgnoreCase(piece)) continue;
                boolean isOpponentPiece = mid.equalsIgnoreCase("w") != isWhite;
                if (isOpponentPiece) return true;
            }
        }
        return false;
    }

    private void validateCoordinates(int r, int c) {
        if (r < 0 || r > 7 || c < 0 || c > 7) {
            throw new InvalidMoveException("Coordinates out of bounds: (" + r + "," + c + ")");
        }
    }

    private void validateManSimpleMove(int dr, int dc, boolean isWhite) {
        if (Math.abs(dr) != 1 || Math.abs(dc) != 1) {
            throw new InvalidMoveException("Invalid simple move for man.");
        }
        if ((isWhite && dr != -1) || (!isWhite && dr != 1)) {
            throw new InvalidMoveException("Man can only move forward.");
        }
    }

    private void validateKingSimpleMove(String[][] board, int dr, int dc) {
        if (Math.abs(dr) != Math.abs(dc) || dr == 0) {
            throw new InvalidMoveException("Invalid simple move for king.");
        }
        int stepR = dr / Math.abs(dr);
        int stepC = dc / Math.abs(dc);
        for (int i = 1; i < Math.abs(dr); i++) {
            if (!board[stepR * i + (stepR < 0 ? -stepR : 0)][stepC * i + (stepC < 0 ? -stepC : 0)].isEmpty()) {
                throw new InvalidMoveException("Path is blocked for king move.");
            }
        }
    }

    private void removePiece(String[][] board, int r, int c, Game game) {
        String cap = board[r][c];
        board[r][c] = "";
        boolean wasWhite = cap.equalsIgnoreCase("w");
        boolean wasKing = Character.isUpperCase(cap.charAt(0));
        if (wasWhite) {
            if (wasKing) game.setDamaW(game.getDamaW() - 1);
            else game.setPedineW(game.getPedineW() - 1);
        } else {
            if (wasKing) game.setDamaB(game.getDamaB() - 1);
            else game.setPedineB(game.getPedineB() - 1);
        }
    }

    private boolean hasAnyCaptures(String[][] board, Team team) {
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                String cell = board[r][c];
                if (cell.isEmpty()) continue;
                boolean isWhite = cell.equalsIgnoreCase("w");
                if ((team == Team.WHITE) != isWhite) continue;

                boolean isPieceKing = Character.isUpperCase(cell.charAt(0));
                int[][] dirs;

                if (!isPieceKing) {
                    int forwardDir = isWhite ? -1 : 1;
                    dirs = new int[][]{{forwardDir, 1}, {forwardDir, -1}};
                } else {
                    dirs = new int[][]{{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
                }

                for (int[] d : dirs) {
                    int mr = r + d[0], mc = c + d[1];
                    int nr = r + 2 * d[0], nc = c + 2 * d[1];
                    if (mr < 0 || mr > 7 || mc < 0 || mc > 7 || nr < 0 || nr > 7 || nc < 0 || nc > 7) continue;
                    if (!board[nr][nc].isEmpty()) continue;

                    String mid = board[mr][mc];
                    if (mid.isEmpty()) continue;

                    boolean midIsWhite = mid.equalsIgnoreCase("w");
                    if (isWhite != midIsWhite) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void checkEndGame(Game game) {
        int totalW = game.getPedineW() + game.getDamaW();
        int totalB = game.getPedineB() + game.getDamaB();
        if (totalW == 0) {
            game.setPartitaTerminata(true);
            game.setVincitore(Team.BLACK);
        } else if (totalB == 0) {
            game.setPartitaTerminata(true);
            game.setVincitore(Team.WHITE);
        } else if (!hasAnyMoves(game.getBoard(), game.getTurno())) {
            game.setPartitaTerminata(true);
            game.setVincitore(game.getTurno() == Team.WHITE ? Team.BLACK : Team.WHITE);
        }
    }

    private boolean hasAnyMoves(String[][] board, Team team) {
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                String cell = board[r][c];
                if (cell.isEmpty()) continue;
                boolean isWhite = cell.equalsIgnoreCase("w");
                if ((team == Team.WHITE) != isWhite) continue;

                if (hasAnyCaptures(board, team)) return true;

                boolean isKing = Character.isUpperCase(cell.charAt(0));
                if (isKing) {
                    int[][] dirs = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
                    for (int[] d : dirs) {
                        int nr = r + d[0], nc = c + d[1];
                        if (nr >= 0 && nr < 8 && nc >= 0 && nc < 8 && board[nr][nc].isEmpty()) return true;
                    }
                } else {
                    int dr = isWhite ? -1 : 1;
                    int[][] dirs = {{dr, 1}, {dr, -1}};
                    for (int[] d : dirs) {
                        int nr = r + d[0], nc = c + d[1];
                        if (nr >= 0 && nr < 8 && nc >= 0 && nc < 8 && board[nr][nc].isEmpty()) return true;
                    }
                }
            }
        }
        return false;
    }
}