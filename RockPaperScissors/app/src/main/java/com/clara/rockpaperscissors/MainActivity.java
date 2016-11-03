package com.clara.rockpaperscissors;

import android.graphics.Color;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;



/*
* index on available?
* display image for choice (resize, or scale relative to device display size)
* test rotation, saving instance state
* test game state
*
* check for opponent going offline
* listener for this player being found by another player

* what if app crashes? how is data removed from db? Other player times out? Game is abandoned.
* player makes new entry every time app starts, so player will be ok. DBA will need to muck out db

*
* TODO *both* players should have to hit Play Again before new game starts or UI updates.
* FIXME after a game, if one player resets and makes a move, it shows up on the other's players screen.
*
*
* */

public class MainActivity extends AppCompatActivity {

	//Log tag
	private static final String TAG = "MAIN ACTIVITY";

	//game play constants
	private static final String ROCK = "rock";
	private static final String PAPER = "paper";
	private static final String SCISSORS = "scissors";

	//Firebase key constants
	private static final String PLAYERS = "players";
	private static final String GAMES = "games";

	//saved instance state bundle keys
	private static final String PLAYED = "played_bundle_key";
	private static final String AVAILABLE = "available_bundle_key";
	private static final String KEY = "fb_key_bundle_key";

	private static final String OP_PLAYED = "opponent_played_bundle_key";
	private static final String OP_AVAILABLE = "opponent_available_bundle_key";
	private static final String OP_KEY = "opponent_fb_key_bundle_key";

	private static final String PLAYER_1_KEY = "game_player_1_key_bundle_key";
	private static final String PLAYER_2_KEY = "game_player_2_key_bundle_key";
	private static final String PLAYER_1_SCORE = "game_player_1_score_bundle_key";
	private static final String PLAYER_2_SCORE = "game_player_2_score_bundle_key";
	private static final String GAME_KEY = "game_key_bundle_key";


	TextView opponentStatusTV, resultTV;
	ImageView opponentPlayIV, thisPlayerPlayIV, rockIV, paperIV, scissorsIV;

	ImageView[] playerChoices;

	Player thisPlayer;
	Player opponent;

	boolean isPlayer1;

	Game game;

	FirebaseDatabase database;
	DatabaseReference playersReference;
	DatabaseReference gamesReference;

	ValueEventListener mOpponentPlayedListener;


	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		if (savedInstanceState != null) {

			Log.d(TAG, "restoring from instance state");

			//Restore self

			boolean avail = savedInstanceState.getBoolean(AVAILABLE);
			String played = savedInstanceState.getString(PLAYED);
			String key = savedInstanceState.getString(KEY);
			thisPlayer = new Player(avail, played, key);

			//Restore opponent, if present

			boolean opAvailable = savedInstanceState.getBoolean(OP_AVAILABLE);
			String opPlayed = savedInstanceState.getString(OP_PLAYED);
			String opKey = savedInstanceState.getString(OP_KEY);

			if (opKey != null) {
				opponent = new Player(opAvailable, opPlayed, opKey);
			}

			String gamePlayer1 = savedInstanceState.getString(PLAYER_1_KEY);
			String gamePlayer2 = savedInstanceState.getString(PLAYER_1_KEY);
			int player1score = savedInstanceState.getInt(PLAYER_1_SCORE);
			int player2score = savedInstanceState.getInt(PLAYER_2_SCORE);
			String gameKey = savedInstanceState.getString(GAME_KEY);

			if (gameKey != null) {
				game = new Game(gamePlayer1, gamePlayer2, player1score, player2score, gameKey);
			}


		} else {
			//No instance state, first time game is launched. Make new player.
			thisPlayer = new Player(true, null, null);  //available, no play, awaiting key
		}

		database = FirebaseDatabase.getInstance();
		playersReference = database.getReference().child(PLAYERS);
		gamesReference = database.getReference().child(GAMES);

		opponentPlayIV = (ImageView) findViewById(R.id.opponent_play_image);
		thisPlayerPlayIV = (ImageView) findViewById(R.id.player_play_image);

		rockIV = (ImageView) findViewById(R.id.play_rock);
		paperIV = (ImageView) findViewById(R.id.play_paper);
		scissorsIV = (ImageView) findViewById(R.id.play_scissors);

		playerChoices = new ImageView[3];
		playerChoices[0] = rockIV; playerChoices[1] = paperIV ; playerChoices[2] = scissorsIV;

		opponentStatusTV = (TextView) findViewById(R.id.opponent_status_tv);
		resultTV = (TextView) findViewById(R.id.game_result_tv);

		if (opponent == null) {
			findOpponent();   //should not happen if in middle of game, if user rotates phone.
		}

		resultTV.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				reset();
			}
		});

		rockIV.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				highlightUserPlay(view);
				play(ROCK);
			}
		});

		paperIV.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				highlightUserPlay(view);
				play(PAPER);
			}
		});

		scissorsIV.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				highlightUserPlay(view);
				play(SCISSORS);
			}
		});

		enableButtons(false);

	}

	private void saveSelfToDB(String key) {
		//no key? save as new player
		if (key == null) {
			DatabaseReference ref = playersReference.push();
			ref.setValue(thisPlayer);
			thisPlayer.key = ref.getKey();
			Log.d(TAG, "Saved self to db, " + thisPlayer);
		}

		//else if key, create new player / update player with this key
		playersReference.child(thisPlayer.key).setValue(thisPlayer);

		Log.d(TAG, "Saved updated self in  db, " + thisPlayer);
	}


	//Remove self from DB as app pauses. Also are saving player and game state in instance state so
	//if the user is rotating the device or otherwise returning to the app, this data will be restored
	//and written back to the DB.

	private void removeSelfFromDB() {
		playersReference.child(thisPlayer.key).removeValue();
	}

	@Override
	public void onPause() {
		super.onPause();
		Log.d(TAG, "on pause, this player " + thisPlayer);
		removeSelfFromDB();
	}

	@Override
	public void onResume(){
		super.onResume();
		Log.d(TAG, "on resume, this player " + thisPlayer);
		saveSelfToDB(thisPlayer.key);
	}

	@Override
	public void onSaveInstanceState(Bundle bundle) {

		bundle.putBoolean(AVAILABLE, thisPlayer.available);
		bundle.putString(PLAYED, thisPlayer.played);
		bundle.putString(KEY, thisPlayer.key);

		if (opponent != null) {
			bundle.putBoolean(OP_AVAILABLE, opponent.available);
			bundle.putString(OP_PLAYED, opponent.played);
			bundle.putString(OP_KEY, opponent.key);
		}

		if (game != null) {
			bundle.putString(PLAYER_1_KEY, game.player1key);
			bundle.putString(PLAYER_2_KEY, game.player2key);
			bundle.putInt(PLAYER_1_SCORE, game.player1score);
			bundle.putInt(PLAYER_2_SCORE, game.player2score);
			bundle.putString(GAME_KEY, game.key);
		}
	}


	private void findOpponent() {

		//Select a random opponent from the list of players online.

		//get the last entries sorted by key. Since they are added by key, which is sorted by date, this gets the most recent
		Query findOpponent = playersReference.orderByKey().limitToLast(30);

		findOpponent.addValueEventListener(new ValueEventListener() {
			@Override
			public void onDataChange(DataSnapshot dataSnapshot) {

				if (opponent != null) {
					//if there is already an opponent, return.
					return;
				}

				Player possibleOpponent;

				for (DataSnapshot ds : dataSnapshot.getChildren()) {
					possibleOpponent = ds.getValue(Player.class);

					//If this opponent is not us, and is available, select
					if (! ds.getKey().equals(thisPlayer.key) && possibleOpponent.available) {
						opponent = possibleOpponent;
						opponent.key = ds.getKey();
					}
				}

				if (opponent != null) {
					//stop listening for events and notify that opponent is available
					playersReference.removeEventListener(this);
					opponentFound();

				} else {
					Toast.makeText(MainActivity.this, "No players available", Toast.LENGTH_LONG).show();
					listenForDiscovery();
				}
			}


			@Override
			public void onCancelled(DatabaseError databaseError) {
				Log.e(TAG, "find opponent error", databaseError.toException());
			}
		});

	}


	private void listenForDiscovery() {

		//Has a game with this player set as player 2 been created?
		Query haveBeenPaired = gamesReference.orderByChild("player2key").equalTo(thisPlayer.key);

		haveBeenPaired.addValueEventListener(new ValueEventListener() {
			@Override
			public void onDataChange(DataSnapshot dataSnapshot) {

				Game temp = dataSnapshot.getValue(Game.class);

				Log.d(TAG, "Have been paired value event, game is " + temp);

				//first response from db may be that there are no results. Ignore.
				if (dataSnapshot.getValue() == null || temp.player1key == null || temp.player2key == null) {
					Log.d(TAG, "game from DB is empty, ignoring");
				}

				else {


					game = dataSnapshot.getValue(Game.class);
					game.key = dataSnapshot.getKey();
					isPlayer1 = false;
					gamesReference.removeEventListener(this);

					Log.d(TAG, "Have been paired value event, game has data " + game);

					//fetch player 1's info, set as opponent

					Query getPlayer1 = playersReference.child(game.player1key);
					getPlayer1.addListenerForSingleValueEvent(new ValueEventListener() {
						@Override
						public void onDataChange(DataSnapshot dataSnapshot) {
							opponent = dataSnapshot.getValue(Player.class);
							opponent.key = dataSnapshot.getKey();
							Log.d(TAG, "Fetched opponent info " + opponent);
						}

						@Override
						public void onCancelled(DatabaseError databaseError) {
							Log.e(TAG, "fetch player 1", databaseError.toException());
						}
					});
				}
			}

			@Override
			public void onCancelled(DatabaseError databaseError) {
				Log.e(TAG, "listen for pairing", databaseError.toException());
			}
		});

	}



	//Called when this players discovers another player. Other player will be notified that they
	//have been discovered, via listenForDiscovery

	private void opponentFound()  {

		Log.d(TAG, "opponent found, " + opponent);

		opponentStatusTV.setText(getString(R.string.opponent_thinking));

		//set opponent's status to unavailable
		//set this player's status to unavailable

		opponent.available = false;
		playersReference.child(opponent.key).setValue(opponent);
		thisPlayer.available = false;
		playersReference.child(thisPlayer.key).setValue(thisPlayer);

		//Create a new game with both player's keys. Other player will be looking our for
		//a game with their key as player2key
		game = new Game(thisPlayer.key, opponent.key, 0, 0, null);

		//we are player 1
		isPlayer1 = true;

		DatabaseReference newGameRef = gamesReference.push();
		newGameRef.setValue(game);

		game.key = newGameRef.getKey();

		Log.d(TAG, "New game created: " + game);

		// TODO how long to wait for opponent? Time out after 30 seconds or so.

		//And permit play
		enableButtons(true);

	}


	private void notifyOpponentPlayed() {

		Log.d(TAG, "opponent has played, " + opponent);
		Log.d(TAG, "this player played " + thisPlayer);

		String thisPlayerPlay = thisPlayer.played;
		String opponentPlay = opponent.played;

		opponentStatusTV.setText("Opponent is ready...");

		playersReference.removeEventListener(mOpponentPlayedListener);

		//If both have played, reveal result

		if (thisPlayerPlay != null && opponentPlay != null) {

			setDrawableForPlay(thisPlayerPlay, thisPlayerPlayIV);
			thisPlayerPlayIV.setVisibility(View.VISIBLE);
			setDrawableForPlay(opponentPlay, opponentPlayIV);
			opponentPlayIV.setVisibility(View.VISIBLE);

			boolean won = false;

			//Did we win?
			if ( thisPlayerPlay.equals(ROCK) && opponentPlay.equals(SCISSORS) ) {
				won = true;
			}

			if ( thisPlayerPlay.equals(SCISSORS) && opponentPlay.equals(PAPER) ) {
				won = true;
			}

			if ( thisPlayerPlay.equals(PAPER) && opponentPlay.equals(ROCK) ) {
				won = true;
			}

			// a draw?

			String pre = "You played " + thisPlayerPlay + ", your opponent played " + opponentPlay + ".";
			String end = "Tap to play again";
			String res = "";
			if (thisPlayerPlay.equals(opponentPlay)) {
				res = "A draw!";
			}

			else if (won) {
				res = "You win!";

				//winner is responsible for updating score.
				if (isPlayer1) { game.player1score++; } else { game.player2score++; }

				gamesReference.child(game.key).setValue(game);


			} else {
				res = "Opponent wins!";

			}


			resultTV.setText(String.format("%s %s %s", pre, res, end));

			resultTV.setVisibility(View.VISIBLE);

		}

	}


	private void enableButtons(boolean enabled) {
		rockIV.setEnabled(enabled);
		paperIV.setEnabled(enabled);
		scissorsIV.setEnabled(enabled);

	}



	private void play(String choice) {

		//disable buttons
		//await opponent choice, if opponent has not yet played.
		//once both players have played, then

		enableButtons(false);

		thisPlayer.played = choice;

		Log.d(TAG, "this player has played, " + thisPlayer);

		playersReference.child(thisPlayer.key).setValue(thisPlayer);

		Log.d(TAG, "opponent is currently " + opponent);

		Query mAwaitOpponentPlay = playersReference.child(opponent.key);

		mOpponentPlayedListener = new ValueEventListener() {
			@Override
			public void onDataChange(DataSnapshot dataSnapshot) {

				//This should happen once opponent plays
				//should have one result....

				Log.d(TAG, "Opponent data fetched, check if they have made their play");

				//todo what to do if opponent leaves game, this will have no results.
				//if opponent has left then notify user, do they want to search for another opponent(?)

				if (dataSnapshot == null || dataSnapshot.getValue(Player.class) == null) {
					Log.d(TAG, "No opponent found, searching for new opponent");
					Toast.makeText(MainActivity.this, "Lost opponent, searching for new opponent", Toast.LENGTH_LONG).show();
					findOpponent();
					playersReference.removeEventListener(this);
				}

				else {
					Player opponentData = dataSnapshot.getValue(Player.class);

					if (opponentData.played == null) {
						//ignore
						Log.d(TAG, "opponent has not yet played");
						return;

					} else {
						//opponent play has been made
						opponent = opponentData;
						opponentData.key = dataSnapshot.getKey();
						Log.d(TAG, "opponent has played" + opponent);

						notifyOpponentPlayed();

						playersReference.removeEventListener(this);
					}
				}
			}

			@Override
			public void onCancelled(DatabaseError databaseError) {

			}
		};

		mAwaitOpponentPlay.addValueEventListener(mOpponentPlayedListener);

	}


	private void reset() {

		unHighlightPlay();
		enableButtons(true);
		opponentPlayIV.setVisibility(View.INVISIBLE);
		thisPlayerPlayIV.setVisibility(View.INVISIBLE);
		resultTV.setVisibility(View.INVISIBLE);

		thisPlayer.played = null;
		opponent.played = null;

		opponentStatusTV.setText("New game, make your choice...");
		playersReference.child(thisPlayer.key).child("played").setValue(null);
		playersReference.child(opponent.key).child("played").setValue(null);

	}


	private void highlightUserPlay(View view) {
		view.setBackgroundColor(Color.YELLOW);
		view.setPadding(5, 5, 5, 5);
	}

	private void unHighlightPlay() {
		for (ImageView v : playerChoices) {
			v.setBackgroundColor(Color.WHITE);
			v.setPadding(0, 0, 0, 0);
		}
	}


	private void setDrawableForPlay(String play, ImageView v) {

		if (play.equals(ROCK)) {
			v.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.rock));
		}

		else if (play.equals(SCISSORS)) {
			v.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.scissors));
		}
		else if (play.equals(PAPER)) {
			v.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.paper));
		}

	}





}
