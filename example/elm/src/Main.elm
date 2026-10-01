port module Main exposing (main)

{-| A tiny inbox, wired for hive-cljs.

The whole model is sent out through `hiveState` after every change, so a
scenario can assert on state (`:expect-state`) and on rendering (`:expect-text`)
separately. Outside a scenario nothing subscribes and the port is a no-op.

-}

import Browser
import Html exposing (Html, button, h1, input, li, p, text, ul)
import Html.Attributes exposing (id, placeholder, value)
import Html.Events exposing (onClick, onInput)
import Json.Encode as Encode


port hiveState : Encode.Value -> Cmd msg


type alias Model =
    { draft : String
    , items : List String
    }


type Msg
    = Draft String
    | Add
    | Clear


main : Program () Model Msg
main =
    Browser.element
        { init = \_ -> publish { draft = "", items = [ "welcome" ] }
        , update = update
        , view = view
        , subscriptions = \_ -> Sub.none
        }


update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
    case msg of
        Draft s ->
            publish { model | draft = s }

        Add ->
            if String.isEmpty (String.trim model.draft) then
                ( model, Cmd.none )

            else
                publish { model | draft = "", items = model.items ++ [ String.trim model.draft ] }

        Clear ->
            publish { model | items = [] }


{-| Every state change goes out through the port: the probe keeps the latest.
-}
publish : Model -> ( Model, Cmd Msg )
publish model =
    ( model, hiveState (encode model) )


encode : Model -> Encode.Value
encode model =
    Encode.object
        [ ( "draft", Encode.string model.draft )
        , ( "items", Encode.list Encode.string model.items )
        ]


view : Model -> Html Msg
view model =
    Html.div []
        [ h1 [] [ text "Inbox" ]
        , input [ id "draft", placeholder "new message", value model.draft, onInput Draft ] []
        , button [ id "add", onClick Add ] [ text "Add" ]
        , button [ id "clear", onClick Clear ] [ text "Clear" ]
        , p [ id "count" ] [ text (countLabel (List.length model.items)) ]
        , ul [ id "items" ] (List.map (\m -> li [] [ text m ]) model.items)
        ]


countLabel : Int -> String
countLabel n =
    if n == 1 then
        "1 message"

    else
        String.fromInt n ++ " messages"
