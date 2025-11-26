function hashString( str ) {

	let hash = 0;
	for (let i = 0; i < str.length; i ++) {

		const char = str.charCodeAt( i );
		hash = ( ( hash << 5 ) - hash ) + char;
		hash |= 0;

	}

	return hash.toString( 16 );

}

function hashSceneFast( scene ) {

	const data = [];

	scene.traverse( ( obj ) => {

		if (obj.isMesh) {

			const geomHash = obj.geometry.uuid;
			const matHash = obj.material.uuid;
			const transformHash = `${obj.position.x},${obj.position.y},${obj.position.z},${obj.rotation.x},${obj.rotation.y},${obj.rotation.z},${obj.scale.x},${obj.scale.y},${obj.scale.z}`;
			data.push( geomHash + matHash + transformHash + obj.name);

		}

	} );

	return hashString( data.join( '|' ) );

}

var lastHash = 0;

function hash_and_update_scene() {

	var hashNow = hashSceneFast( window.editor.scene );

	if (hashNow !== lastHash) {

		console.log(" scene autoupdate ")
		editor.signals.sceneGraphChanged.dispatch();

	}

	lastHash = hashNow;

	setTimeout( hash_and_update_scene, 500 );

}

export function init_field() {

	setTimeout( hash_and_update_scene, 200 );

}
